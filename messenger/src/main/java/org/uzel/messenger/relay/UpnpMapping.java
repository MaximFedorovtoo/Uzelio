package org.uzel.messenger.relay;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort UPnP IGD port mapping: opens one TCP port on the router without any manual
 * forwarding and without a static address. The mapping is temporary and removed on close.
 */
public final class UpnpMapping implements AutoCloseable {
    private static final String SEARCH = "M-SEARCH * HTTP/1.1\r\n"
            + "HOST: 239.255.255.250:1900\r\n"
            + "MAN: \"ssdp:discover\"\r\nMX: 2\r\n"
            + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n";
    private static final Pattern LOCATION = Pattern.compile("(?im)^LOCATION:\\s*(\\S+)\\s*$");
    private static final Pattern SERVICE = Pattern.compile(
            "(?is)<service>(?:(?!</service>).)*?<serviceType>([^<]*(?:WANIPConnection|WANPPPConnection)[^<]*)</serviceType>"
                    + "(?:(?!</service>).)*?<controlURL>([^<]*)</controlURL>(?:(?!</service>).)*?</service>");
    private static final Pattern EXTERNAL_IP = Pattern.compile(
            "(?is)<NewExternalIPAddress>([^<]*)</NewExternalIPAddress>");

    private final String controlUrl;
    private final String serviceType;
    private final String externalAddress;
    private final int port;

    private UpnpMapping(String controlUrl, String serviceType, String externalAddress, int port) {
        this.controlUrl = controlUrl;
        this.serviceType = serviceType;
        this.externalAddress = externalAddress;
        this.port = port;
    }

    /** Opens TCP {@code port} on the gateway and returns the address the gateway reports. */
    public static UpnpMapping open(int port, String internalAddress, int leaseSeconds) throws Exception {
        String location = discover();
        if (location == null) throw new IllegalStateException("UPnP gateway was not found");
        String description = fetch(location);
        Matcher service = SERVICE.matcher(description);
        if (!service.find()) throw new IllegalStateException("Gateway has no WAN connection service");
        String serviceType = service.group(1).trim();
        String control = new URL(new URL(location), service.group(2).trim()).toString();
        String external = soap(control, serviceType, "GetExternalIPAddress", "");
        Matcher address = EXTERNAL_IP.matcher(external);
        String externalAddress = address.find() ? address.group(1).trim() : "";
        soap(control, serviceType, "AddPortMapping",
                "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort>"
                        + "<NewProtocol>TCP</NewProtocol><NewInternalPort>" + port + "</NewInternalPort>"
                        + "<NewInternalClient>" + xml(internalAddress) + "</NewInternalClient>"
                        + "<NewEnabled>1</NewEnabled>"
                        + "<NewPortMappingDescription>Uzelio relay</NewPortMappingDescription>"
                        + "<NewLeaseDuration>" + leaseSeconds + "</NewLeaseDuration>");
        return new UpnpMapping(control, serviceType, externalAddress, port);
    }

    /** Address reported by the gateway; may be private when the provider uses carrier NAT. */
    public String externalAddress() {
        return externalAddress;
    }

    public int port() {
        return port;
    }

    /** True when the provider likely hides the address behind carrier-grade NAT. */
    public boolean carrierNat() {
        if (externalAddress.isEmpty()) return true;
        if (externalAddress.startsWith("10.") || externalAddress.startsWith("192.168.")
                || externalAddress.startsWith("127.") || externalAddress.startsWith("169.254.")) return true;
        if (externalAddress.startsWith("100.")) {
            try {
                int second = Integer.parseInt(externalAddress.split("\\.")[1]);
                return second >= 64 && second <= 127;
            } catch (RuntimeException ignored) {
                return true;
            }
        }
        if (externalAddress.startsWith("172.")) {
            try {
                int second = Integer.parseInt(externalAddress.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (RuntimeException ignored) {
                return true;
            }
        }
        return false;
    }

    private static String discover() throws Exception {
        try (MulticastSocket socket = new MulticastSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));
            socket.setSoTimeout(2500);
            byte[] query = SEARCH.getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(query, query.length,
                    InetAddress.getByName("239.255.255.250"), 1900));
            long deadline = System.nanoTime() + 3_000_000_000L;
            byte[] buffer = new byte[4096];
            while (System.nanoTime() < deadline) {
                DatagramPacket answer = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(answer);
                } catch (java.net.SocketTimeoutException quiet) {
                    continue;
                }
                String text = new String(answer.getData(), 0, answer.getLength(), StandardCharsets.US_ASCII);
                Matcher location = LOCATION.matcher(text);
                if (location.find()) return location.group(1).trim();
            }
        }
        return null;
    }

    private static String fetch(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestMethod("GET");
        try (InputStream input = connection.getInputStream()) {
            return read(input);
        } finally {
            connection.disconnect();
        }
    }

    private static String soap(String control, String serviceType, String action, String arguments) throws Exception {
        String body = "<?xml version=\"1.0\"?>\r\n"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + serviceType + "\">" + arguments + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(control).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        connection.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + action + "\"");
        connection.setFixedLengthStreamingMode(payload.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload);
        }
        int status = connection.getResponseCode();
        try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            String answer = input == null ? "" : read(input);
            if (status >= 400) throw new IllegalStateException("Gateway rejected " + action + " (HTTP " + status + ")");
            return answer;
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Removes the temporary mapping; safe to call more than once. */
    @Override public void close() {
        try {
            soap(controlUrl, serviceType, "DeletePortMapping",
                    "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort>"
                            + "<NewProtocol>TCP</NewProtocol>");
        } catch (Exception ignored) {
            // A gateway may already have dropped the lease; nothing else to do.
        }
    }
}
