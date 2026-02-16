import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

public class WolAuthServer {

    // ==== CONFIG ====
    private static final int PORT = 12221;                         // Bind to VPN-only interface or firewall to 10.8.0.0/24
    private static final String BIND_ADDR = "0.0.0.0";             // (opt) change to your VPN IP to avoid exposing externally
    private static final String WAKE_SCRIPT = "/usr/local/bin/wake_db_server";
    private static final Path CLIENT_PUB_PEM = Paths.get("/etc/wolauth/client_pub.pem"); // client's current public key
    private static final Path SERVER_PRIV_PEM = Paths.get("/etc/wolauth/server_priv.pem");
    private static final Path SERVER_PUB_PEM  = Paths.get("/etc/wolauth/server_pub.pem");

    // RSA settings
    private static final int RSA_BITS = 2048;

    // ==== PROTOCOL FIELDS ====
    // Client sends one line JSON-ish (no lib): base64url fields joined by '|'
    // REQ = base64url("SEND_WOL|nonceB64|tsMillis|newServerKeyRequested(true/false)|sigB64")
    // SIG over the ASCII bytes of: "SEND_WOL|nonceB64|tsMillis|newServerKeyRequested"
    //
    // Server replies with:
    // RESP = base64url("OK|nonceB64|cipherB64")   where cipherB64 = RSA-OAEP(oldClientPub, newServerPubPemBytes)
    // or     base64url("ERR|reason")
    //
    // NOTE: We rotate the server keypair every success; client decrypts new server pub with its OLD private key.

    public static void main(String[] args) throws Exception {
        // Ensure key dir exists
        Files.createDirectories(CLIENT_PUB_PEM.getParent());
        // Ensure we have a server keypair at startup
        if (!Files.exists(SERVER_PRIV_PEM) || !Files.exists(SERVER_PUB_PEM)) {
            KeyPair kp = genRsa();
            writePemPrivate(SERVER_PRIV_PEM, kp.getPrivate());
            writePemPublic(SERVER_PUB_PEM, kp.getPublic());
        }

        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress(InetAddress.getByName(BIND_ADDR), PORT));
            log("WolAuthServer listening on " + BIND_ADDR + ":" + PORT);
            while (true) {
                try (Socket s = ss.accept()) {
                    s.setSoTimeout(10_000);
                    handle(s);
                } catch (Exception e) {
                    log("Connection error: " + e.getMessage());
                }
            }
        }
    }

    private static void handle(Socket s) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

        String line = br.readLine();
        if (line == null) return;

        // Decode outer base64url
        String req = new String(Base64.getUrlDecoder().decode(line), StandardCharsets.UTF_8);
        String[] parts = req.split("\\|");
        if (parts.length != 5) {
            writeResp(bw, err("bad_parts"));
            return;
        }

        String cmd = parts[0];              // "SEND_WOL"
        String nonceB64 = parts[1];
        String tsStr = parts[2];
        String wantRotate = parts[3];       // "true"/"false"
        String sigB64 = parts[4];

        if (!"SEND_WOL".equals(cmd)) {
            writeResp(bw, err("bad_cmd"));
            return;
        }

        long ts;
        try { ts = Long.parseLong(tsStr); } catch (Exception e) { writeResp(bw, err("bad_ts")); return; }
        long now = Instant.now().toEpochMilli();
        if (Math.abs(now - ts) > 60_000) { // 60s skew limit
            writeResp(bw, err("ts_skew"));
            return;
        }

        // Verify signature (RSASSA-PSS with SHA-256)
        PublicKey clientPub = readPemPublic(CLIENT_PUB_PEM);
        String signedPayload = cmd + "|" + nonceB64 + "|" + tsStr + "|" + wantRotate;
        byte[] sig = Base64.getUrlDecoder().decode(sigB64);
        if (!verifyPss(clientPub, signedPayload.getBytes(StandardCharsets.UTF_8), sig)) {
            writeResp(bw, err("bad_sig"));
            return;
        }

        // Authorized — run wake script
        int exit = runWakeScript();
        if (exit != 0) {
            writeResp(bw, err("wake_fail:" + exit));
            return;
        }

        // Optionally rotate server key (you asked to rotate every time; we do it on success)
        KeyPair newServer = genRsa();
        writePemPrivate(SERVER_PRIV_PEM, newServer.getPrivate());
        writePemPublic(SERVER_PUB_PEM,  newServer.getPublic());

        // Encrypt the NEW server public key (PEM bytes) to the client's *current (old)* PUBLIC key
        byte[] newServerPubPem = Files.readAllBytes(SERVER_PUB_PEM);
        byte[] cipher = rsaOaepEncrypt(clientPub, newServerPubPem);

        String respInner = "OK|" + nonceB64 + "|" + Base64.getUrlEncoder().withoutPadding().encodeToString(cipher);
        String respOuter = Base64.getUrlEncoder().withoutPadding().encodeToString(respInner.getBytes(StandardCharsets.UTF_8));
        writeResp(bw, respOuter);
    }

    private static int runWakeScript() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sudo", WAKE_SCRIPT);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) log("[wake] " + line);
        }
        int exit = p.waitFor();
        log("wake_db_server exit=" + exit);
        return exit;
    }

   private static boolean verifyPss(PublicKey pub, byte[] data, byte[] sig) throws Exception {
    Signature s = Signature.getInstance("RSASSA-PSS");
    s.setParameter(new PSSParameterSpec(
        "SHA-256", 
        "MGF1", 
        MGF1ParameterSpec.SHA256, 
        32, 
        1
    ));
    s.initVerify(pub);
    s.update(data);
    return s.verify(sig);
}

    private static byte[] rsaOaepEncrypt(PublicKey pub, byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        c.init(Cipher.ENCRYPT_MODE, pub, new OAEPParameterSpec("SHA-256","MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return c.doFinal(plain);
    }

    private static KeyPair genRsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(RSA_BITS);
        return g.generateKeyPair();
    }

    // ===== PEM helpers (minimal DER<->PEM) =====
    private static void writePemPublic(Path p, PublicKey k) throws Exception {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(k.getEncoded());
        String pem = "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
        Files.createDirectories(p.getParent());
        Files.writeString(p, pem, StandardCharsets.US_ASCII, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
    private static void writePemPrivate(Path p, PrivateKey k) throws Exception {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(k.getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
        Files.createDirectories(p.getParent());
        Files.writeString(p, pem, StandardCharsets.US_ASCII, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
    private static PublicKey readPemPublic(Path p) throws Exception {
        String pem = Files.readString(p, StandardCharsets.US_ASCII).replaceAll("-----BEGIN PUBLIC KEY-----","")
                .replaceAll("-----END PUBLIC KEY-----","").replaceAll("\\s+","");
        byte[] der = Base64.getDecoder().decode(pem);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private static void writeResp(BufferedWriter bw, String payload) throws IOException {
        bw.write(payload);
        bw.write("\n");
        bw.flush();
    }
    private static String err(String why) {
        String inner = "ERR|" + why;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(inner.getBytes(StandardCharsets.UTF_8));
    }
    private static void log(String m){ System.out.println("[WolAuthServer] " + m); }
}

