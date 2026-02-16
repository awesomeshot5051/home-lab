import java.net.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;

public class HeartbeatServer {

    private static final int PORT = 46317;
    private static final int TIMEOUT_MS = 15000; // 15 seconds
    private static final long GRACE_PERIOD_MS = 300000; // 5 minutes
    private static boolean verboseMode = false;

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static volatile boolean shutdownRequested = false;

    private static final String MIDDLEMAN_SCRIPT = "/usr/local/bin/heartbeat-middleman.sh";

    // Track all connected clients
    private static final ConcurrentHashMap<String, ClientSession> clients = new ConcurrentHashMap<>();

    // Dedicated socket for each purpose
    private static DatagramSocket mainSocket;
    private static ExecutorService threadPool;

    static class ClientSession {
        final String clientId;
        final InetAddress address;
        long lastHeartbeat;

        ClientSession(String clientId, InetAddress address) {
            this.clientId = clientId;
            this.address = address;
            this.lastHeartbeat = System.currentTimeMillis();
        }

        void updateHeartbeat() {
            this.lastHeartbeat = System.currentTimeMillis();
        }

        boolean isTimedOut() {
            return System.currentTimeMillis() - lastHeartbeat > TIMEOUT_MS;
        }

        long getTimeSinceLastHeartbeat() {
            return System.currentTimeMillis() - lastHeartbeat;
        }
    }

    public static void main(String[] args) {

        // Enable verbose mode if requested
        for (String arg : args) {
            if ("-v".equals(arg) || "--verbose".equals(arg)) {
                verboseMode = true;
                break;
            }
        }

        System.out.println("============================================================");
        System.out.println(LocalDateTime.now() + " Heartbeat Server starting...");
        System.out.println("Listening on UDP port: " + PORT);
        System.out.println("Timeout: " + TIMEOUT_MS + "ms | Grace Period: " +
                (GRACE_PERIOD_MS / 1000) + "s");
        if (verboseMode) System.out.println("Verbose logging enabled.");
        System.out.println("Multi-client support: ENABLED");
        System.out.println("============================================================");

        threadPool = Executors.newCachedThreadPool();

        try {
            mainSocket = new DatagramSocket(PORT);
            mainSocket.setSoTimeout(1000); // Short timeout to allow checking for shutdown

            // Start timeout monitor thread
            threadPool.submit(HeartbeatServer::monitorTimeouts);

            byte[] buf = new byte[256];

            while (!shutdownRequested) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    mainSocket.receive(packet);

                    String msg = new String(packet.getData(), 0, packet.getLength()).trim();
                    InetAddress clientAddress = packet.getAddress();
                    String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
                    if ("AYA".equalsIgnoreCase(msg)) {
                        System.out.printf("[%s] AYA probe received from %s - responding YES|READY%n",
                                timestamp, clientAddress.getHostAddress());

                        byte[] reply = "YES|READY".getBytes();
                        DatagramPacket response = new DatagramPacket(reply, reply.length,
                                clientAddress, packet.getPort());
                        mainSocket.send(response);
                        continue;  // Skip further processing for AYA
                    }
                    // Parse message format: "<clientId>|<command>" or "HELLO|<clientId>"
                    String[] parts = msg.split("\\|", 2);

                    // Handles messages with client ID and command
                    if (parts.length == 2) {
                        String firstPart = parts[0];
                        String secondPart = parts[1];
                        // Handle HELLO handshake
                        if ("HELLO".equalsIgnoreCase(firstPart)) {
                            String clientId = secondPart;
                            handleHello(clientId, clientAddress, packet.getPort(), timestamp);
                            continue;
                        }

                        // Handle messages with clientId prefix
                        String clientId = firstPart;
                        String command = secondPart;

                        if ("HEARTBEAT".equalsIgnoreCase(command)) {
                            handleHeartbeat(clientId, clientAddress, timestamp);
                            continue;
                        }

                        if ("KILL".equalsIgnoreCase(command)) {
                            handleKill(clientId, clientAddress, timestamp);
                            break;
                        }
                    }

                    // Handle legacy format (backwards compatibility)
                    if ("HEARTBEAT".equalsIgnoreCase(msg)) {
                        String legacyId = "legacy_" + clientAddress.getHostAddress();
                        handleHeartbeat(legacyId, clientAddress, timestamp);
                        continue;
                    }

                    if ("KILL".equalsIgnoreCase(msg)) {
                        System.out.printf("[%s] Legacy KILL signal from %s%n",
                                timestamp, clientAddress.getHostAddress());
                        shutdownRequested = true;
                        callMiddleman();
                        break;
                    }

                    if (verboseMode) {
                        System.out.printf("[%s] Unknown message from %s: %s%n",
                                timestamp, clientAddress.getHostAddress(), msg);
                    }

                } catch (SocketTimeoutException e) {
                    // Normal - just allows us to check shutdownRequested periodically
                }
            }

        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanup();
        }

        System.out.println("HeartbeatServer exiting cleanly.");
        System.exit(0);
    }

    private static void handleHello(String clientId, InetAddress address, int port, String timestamp) {
        System.out.printf("[%s] HELLO from client '%s' (%s)%n",
                timestamp, clientId, address.getHostAddress());

        // Register or update client session
        ClientSession session = clients.computeIfAbsent(clientId,
                k -> new ClientSession(clientId, address));
        session.updateHeartbeat();

        // Send WELCOME response
        try {
            byte[] welcomeBytes = "WELCOME".getBytes();
            DatagramPacket response = new DatagramPacket(welcomeBytes, welcomeBytes.length,
                    address, port);
            mainSocket.send(response);

            System.out.printf("[%s] WELCOME sent to '%s'%n", timestamp, clientId);
            System.out.println("Active clients: " + clients.size());
        } catch (Exception e) {
            System.err.println("Failed to send WELCOME to " + clientId + ": " + e.getMessage());
        }
    }

    private static void handleHeartbeat(String clientId, InetAddress address, String timestamp) {
        ClientSession session = clients.get(clientId);

        if (session == null) {
            // Client not registered - create session
            session = new ClientSession(clientId, address);
            clients.put(clientId, session);
            System.out.printf("[%s] New client '%s' registered (no HELLO received)%n",
                    timestamp, clientId);
        }

        session.updateHeartbeat();

        if (verboseMode) {
            System.out.printf("[%s] Heartbeat from '%s' (%s) - Active clients: %d%n",
                    timestamp, clientId, address.getHostAddress(), clients.size());
        }
    }

    private static void handleKill(String clientId, InetAddress address, String timestamp) {
        System.out.printf("[%s] KILL signal from client '%s' (%s)%n",
                timestamp, clientId, address.getHostAddress());

        // Remove this specific client
        clients.remove(clientId);
        System.out.println("Client '" + clientId + "' removed. Remaining clients: " + clients.size());

        // Only trigger shutdown if ALL clients are gone
        if (clients.isEmpty()) {
            System.out.println("All clients disconnected. Initiating shutdown...");
            shutdownRequested = true;
            callMiddleman();
        } else {
            System.out.println("Other clients still connected. Server continues running.");
        }
    }

    private static void monitorTimeouts() {
        System.out.println("Timeout monitor thread started");

        while (!shutdownRequested) {
            try {
                Thread.sleep(5000); // Check every 5 seconds

                long now = System.currentTimeMillis();
                List<String> timedOutClients = new ArrayList<>();

                for (Map.Entry<String, ClientSession> entry : clients.entrySet()) {
                    ClientSession session = entry.getValue();
                    long timeSince = now - session.lastHeartbeat;

                    if (timeSince > TIMEOUT_MS) {
                        timedOutClients.add(entry.getKey());
                    }
                }

                // Handle timed out clients
                for (String clientId : timedOutClients) {
                    ClientSession session = clients.get(clientId);
                    if (session != null) {
                        System.out.println("\n============================================================");
                        System.out.printf("Client '%s' timed out (no heartbeat for %d seconds)%n",
                                clientId, (now - session.lastHeartbeat) / 1000);
                        System.out.println("Starting grace period for this client...");
                        System.out.println("============================================================");

                        // Start grace period in separate thread
                        String finalClientId = clientId;
                        threadPool.submit(() -> handleClientGracePeriod(finalClientId, session));
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("Timeout monitor thread stopped");
    }

    private static void handleClientGracePeriod(String clientId, ClientSession session) {
        long graceStart = System.currentTimeMillis();
        long lastHeartbeatAtStart = session.lastHeartbeat;

        System.out.printf("Grace period started for client '%s'%n", clientId);

        while (System.currentTimeMillis() - graceStart < GRACE_PERIOD_MS) {
            // Check if client has sent a heartbeat during grace period
            if (session.lastHeartbeat > lastHeartbeatAtStart) {
                System.out.printf("Client '%s' heartbeat resumed! Grace period cancelled.%n", clientId);
                return;
            }

            try {
                Thread.sleep(5000);
                long remaining = (GRACE_PERIOD_MS - (System.currentTimeMillis() - graceStart)) / 1000;
                System.out.printf("Grace period for '%s': %d seconds remaining...%n",
                        clientId, remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        // Grace period expired
        System.out.printf("Grace period expired for client '%s'. Removing client.%n", clientId);
        clients.remove(clientId);

        System.out.println("Remaining active clients: " + clients.size());

        // If all clients are gone, trigger shutdown
        if (clients.isEmpty()) {
            System.out.println("All clients timed out. Triggering controller shutdown...");
            shutdownRequested = true;
            callMiddleman();
        }
    }

    private static void callMiddleman() {
        try {
            System.out.println("Calling middleman with mode=" + 2);

            new ProcessBuilder(MIDDLEMAN_SCRIPT, String.valueOf(2))
                    .start();

            System.out.println("Middleman invoked.");

        } catch (Exception e) {
            System.err.println("Middleman invocation failed: " + e.getMessage());
        }
    }

    private static void cleanup() {
        System.out.println("Cleaning up resources...");

        if (mainSocket != null && !mainSocket.isClosed()) {
            mainSocket.close();
        }

        if (threadPool != null) {
            threadPool.shutdownNow();
        }

        clients.clear();
    }
}