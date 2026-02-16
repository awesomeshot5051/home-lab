import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;

public class MockHeartbeatListener {

    public static void main(String[] args) throws Exception {
        int port = 46317;
        boolean serverOnline = false; // toggle to simulate server up/down

        DatagramSocket socket = new DatagramSocket(port);
        System.out.println("[" + LocalDateTime.now() + "] MockHeartbeatListener running on UDP port " + port + "...");

        byte[] buf = new byte[1024];

        while (true) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);

            InetAddress sender = packet.getAddress();
            int senderPort = packet.getPort();
            int length = packet.getLength();
            String message = new String(packet.getData(), 0, length, StandardCharsets.UTF_8).trim();

            System.out.printf("[" + LocalDateTime.now() + "] Received packet from %s:%d - length %d bytes: [%s]%n",
                    sender.getHostAddress(), senderPort, length, message);

            byte[] response;

            if ("AYA".equalsIgnoreCase(message)) {
                response = "YES".getBytes(StandardCharsets.UTF_8); // always reply YES
                socket.send(new DatagramPacket(response, response.length, sender, senderPort));
                System.out.printf("Replied [%s] to AYA%n", new String(response));
            } else if ("START_HEARTBEAT".equalsIgnoreCase(message)) {
                response = "ACK".getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(response, response.length, sender, senderPort));
                System.out.println("[" + LocalDateTime.now() + "] Replied [ACK] to START_HEARTBEAT");
                // Simulate server coming online after trigger
                serverOnline = true;
                System.out.println("[" + LocalDateTime.now() + "] "+serverOnline);
            } else if (message.startsWith("HELLO|")) {
                response = "WELCOME".getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(response, response.length, sender, senderPort));
                System.out.println("[" + LocalDateTime.now() + "] Replied [WELCOME] to HELLO");
            } else if (message.contains("|HEARTBEAT")) {
                System.out.printf("[" + LocalDateTime.now() + "] Received HEARTBEAT from client: %s%n", message.split("\\|")[0]);
            } else if (message.contains("|KILL")) {
                System.out.printf("[" + LocalDateTime.now() + "] Received KILL from client: %s%n", message.split("\\|")[0]);
            } else {
                System.out.println("[" + LocalDateTime.now() + "] Unknown message: " + message);
            }
        }
    }
}

