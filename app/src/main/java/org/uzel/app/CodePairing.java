package org.uzel.app;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * LAN pairing with a six digit code: the device that shows the code answers exactly that code,
 * the other device broadcasts it on the local network and receives the full invitation.
 * Nothing leaves the local network and no server is involved.
 */
public final class CodePairing {
    public static final int PORT = 39285;
    private static final String PREFIX = "uzelio-lan-code:";
    private static DatagramSocket server;

    private CodePairing() {}

    /** Answers only the given code for two minutes, then closes itself. */
    public static synchronized void serve(String code, String invitation) {
        stop();
        try {
            DatagramSocket socket = new DatagramSocket(PORT);
            socket.setBroadcast(true);
            server = socket;
            Thread thread = new Thread(() -> {
                byte[] buffer = new byte[8192];
                long deadline = System.currentTimeMillis() + 120_000L;
                while (System.currentTimeMillis() < deadline && !socket.isClosed()) {
                    try {
                        socket.setSoTimeout(2000);
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);
                        String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                        if (!text.equals(PREFIX + code)) continue;
                        byte[] reply = invitation.getBytes(StandardCharsets.UTF_8);
                        socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                    } catch (SocketTimeoutException ignored) {
                        /* Keep waiting until the deadline. */
                    } catch (IOException closed) {
                        break;
                    }
                }
                stop();
            }, "uzelio-code-server");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException unavailable) {
            stop();
        }
    }

    public static synchronized void stop() {
        DatagramSocket socket = server;
        server = null;
        if (socket != null) try { socket.close(); } catch (RuntimeException ignored) { }
    }

    /** Broadcasts the code and returns the first invitation, or null when nobody answers. */
    public static String lookup(String code, long timeoutMillis) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            byte[] query = (PREFIX + code).getBytes(StandardCharsets.UTF_8);
            for (InetAddress target : new InetAddress[]{
                    InetAddress.getByName("255.255.255.255"), InetAddress.getByName("127.0.0.1")}) {
                try { socket.send(new DatagramPacket(query, query.length, target, PORT)); }
                catch (IOException ignored) { /* Other address may still work. */ }
            }
            long deadline = System.currentTimeMillis() + timeoutMillis;
            byte[] buffer = new byte[16384];
            while (System.currentTimeMillis() < deadline) {
                try {
                    socket.setSoTimeout(1500);
                    DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                    socket.receive(reply);
                    String value = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8).trim();
                    if (value.startsWith("uzelio-chat:")) return value;
                } catch (SocketTimeoutException ignored) {
                    /* Wait for the rest of the deadline. */
                }
            }
        } catch (IOException unavailable) {
            /* LAN pairing is optional. */
        }
        return null;
    }
}
