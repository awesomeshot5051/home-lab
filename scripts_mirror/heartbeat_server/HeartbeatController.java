import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * HeartbeatController — waits for START_HEARTBEAT.
 * On trigger → switch to HeartbeatServer via middleman.
 * On timeout → schedule shutdown.
 * Includes full SIGTERM handling and AYA probe support.
 *
 * Multi-Client Architecture:
 * - Responds "NO" to AYA (server not running yet)
 * - Responds "ACK" to START_HEARTBEAT and launches server
 * - Once server is running, controller terminates
 */
public class HeartbeatController {
    private static final int PORT = 46317;
    private static final String TRIGGER_MESSAGE = "START_HEARTBEAT";
    private static final String ACK_MESSAGE = "ACK";
    private static final int BUFFER_SIZE = 512;
    private static final int TIMEOUT_MS = 180000; // 3 minutes
    private static final String MIDDLEMAN_SCRIPT = "/usr/local/bin/heartbeat-middleman.sh";

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static volatile boolean shutdownRequested = false;
    private static Process shutdownProcess = null;

    public static void main(String[] args) {

        // === Shutdown Hook ===
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownRequested = true;
            System.out.println("[ShutdownHook] SIGTERM received — closing controller.");
        }));

        System.out.println("==============================================");
        System.out.println("Heartbeat Controller - Multi-Client Edition");
        System.out.println("Listening for trigger packets on UDP port: " + PORT);
        System.out.println("Timeout: " + (TIMEOUT_MS / 1000) + "s (3 minutes)");
        System.out.println("Responds 'NO' to AYA probes (server not running)");
        System.out.println("==============================================");

        boolean shouldStartHeartbeat = false;

        try (DatagramSocket socket = new DatagramSocket(PORT)) {
            socket.setSoTimeout(TIMEOUT_MS);
            byte[] buffer = new byte[BUFFER_SIZE];

            while (!shutdownRequested) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                    try {
                        socket.receive(packet);
                    } catch (SocketException se) {
                        if (shutdownRequested) {
                            System.out.println("Socket closed due to shutdown.");
                            break;
                        }
                        throw se;
                    }

                    String msg = new String(packet.getData(), 0, packet.getLength()).trim();
                    InetAddress sender = packet.getAddress();
                    int senderPort = packet.getPort();
                    String source = sender.getHostAddress();
                    String timestamp = LocalDateTime.now().format(TIME_FORMATTER);

                    System.out.printf("[%s] Received packet from %s:%d — \"%s\"%n",
                            timestamp, source, senderPort, msg);

                    // === Handle AYA Probe ===
                    // Controller responds "NO" because HeartbeatServer is not running yet
                    if ("AYA".equalsIgnoreCase(msg)) {
                        System.out.println("[" + timestamp + "] AYA probe received - responding NO (server not running)");

                        byte[] reply = "NO".getBytes();
                        DatagramPacket replyPacket =
                                new DatagramPacket(reply, reply.length, sender, senderPort);
                        socket.send(replyPacket);
                        System.out.println("NO sent to " + source);
                        continue;
                    }

                    // === Handle START_HEARTBEAT Trigger ===
                    if (TRIGGER_MESSAGE.equalsIgnoreCase(msg)) {
                        System.out.println("\n" + "=".repeat(60));
                        System.out.println("[" + timestamp + "] ✓ Valid START_HEARTBEAT trigger received from " + source);
                        System.out.println("=".repeat(60));

                        cancelShutdownIfScheduled();

                        // Send ACK to client
                        byte[] ack = ACK_MESSAGE.getBytes();
                        DatagramPacket ackPacket =
                                new DatagramPacket(ack, ack.length, sender, senderPort);
                        socket.send(ackPacket);
                        System.out.println("✓ ACK sent to client at " + source);

                        shouldStartHeartbeat = true;
                        break; // Exit loop to start server
                    }

                    // === Ignore Invalid Packets ===
                    System.out.println("⚠ Ignoring invalid/unknown packet: \"" + msg + "\"");

                } catch (SocketTimeoutException e) {
                    handleTimeout();
                    break;
                }
            }

        } catch (Exception e) {
            System.err.println("Controller error: " + e.getMessage());
            e.printStackTrace();
        }

        // === Launch HeartbeatServer ===
        if (shouldStartHeartbeat && !shutdownRequested) {
            System.out.println("\n" + "=".repeat(60));
            System.out.println("Transitioning from Controller → HeartbeatServer");
            System.out.println("Calling middleman to start multi-client server...");
            System.out.println("=".repeat(60));

            callMiddleman(1); // mode 1 = switch to HeartbeatServer

            // Give middleman time to execute
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("HeartbeatController terminated.");
    }

    private static void handleTimeout() {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        System.out.println("\n" + "=".repeat(60));
        System.out.println("[" + timestamp + "] TIMEOUT: No START_HEARTBEAT received.");
        System.out.println("No clients connected within timeout period.");
        System.out.println("Scheduling system shutdown...");
        System.out.println("=".repeat(60));

        try {
            String logMsg = "[" + timestamp + "] Controller timeout - no clients connected - scheduling shutdown\n";
            Files.write(Paths.get("heartbeat_shutdown.log"), logMsg.getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("Log write failed: " + e.getMessage());
        }

        scheduleShutdown();
    }

    private static void scheduleShutdown() {
        try {
            ProcessBuilder pb = new ProcessBuilder("sudo", "shutdown", "-h", "+1",
                    "Heartbeat controller timeout - no clients");
            shutdownProcess = pb.start();
            System.out.println("✓ Shutdown scheduled for 1 minute from now.");
        } catch (Exception e) {
            System.err.println("Failed to schedule shutdown: " + e.getMessage());
        }
    }

    private static void cancelShutdownIfScheduled() {
        if (shutdownProcess == null) {
            System.out.println("No shutdown scheduled - nothing to cancel.");
            return;
        }

        try {
            System.out.println("Canceling previously scheduled shutdown...");
            ProcessBuilder pb = new ProcessBuilder("sudo", "shutdown", "-c");
            pb.inheritIO();
            Process p = pb.start();
            int exit = p.waitFor();

            if (exit == 0) {
                System.out.println("✓ Shutdown successfully canceled.");
            } else {
                System.err.println("✗ Failed to cancel shutdown (exit code: " + exit + ")");
            }

            shutdownProcess = null;

        } catch (Exception e) {
            System.err.println("Failed to cancel shutdown: " + e.getMessage());
        }
    }

    /**
     * Calls the middleman script to switch modes.
     * Mode 1 = Switch from Controller to HeartbeatServer
     * Mode 2 = Shutdown (not used by controller)
     */
    private static void callMiddleman(int mode) {
        try {
            System.out.println("Invoking middleman script with mode " + mode + "...");

            ProcessBuilder pb = new ProcessBuilder(MIDDLEMAN_SCRIPT, String.valueOf(mode));
            Process process = pb.start();

            // Fire and forget - we do NOT wait for it
            System.out.println("✓ Middleman process launched.");
            System.out.println("Controller will now terminate to allow server to bind to port " + PORT);

        } catch (Exception e) {
            System.err.println("Middleman invocation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}