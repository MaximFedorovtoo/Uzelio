package org.uzel.messenger.chat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Best-effort hint for the "address reachable by your contact" field.
 *
 * <p>It returns a private IPv4 address of a physical interface when one exists, otherwise an
 * empty string. It never returns loopback and never claims reachability: an address behind NAT
 * or a mobile carrier gateway still needs a working route or a relay.
 */
public final class LocalAddress {
    private LocalAddress() {}

    public static String preferred() {
        try {
            List<Candidate> candidates = new ArrayList<>();
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue;
                String name = network.getName() == null ? "" : network.getName().toLowerCase(java.util.Locale.ROOT);
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address)) continue;
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    String host = address.getHostAddress();
                    if (host == null || host.isEmpty() || host.indexOf(':') >= 0) continue;
                    candidates.add(new Candidate(rank(name), range(host), address.isSiteLocalAddress() ? 0 : 1, name, host));
                }
            }
            if (candidates.isEmpty()) return "";
            candidates.sort(Comparator.comparingInt(Candidate::range)
                    .thenComparingInt(Candidate::rank)
                    .thenComparingInt(Candidate::local)
                    .thenComparing(Candidate::name));
            return candidates.get(0).host();
        } catch (Exception unavailable) {
            return "";
        }
    }

    /** Home LAN first, then carrier NAT, then virtual switch ranges (Hyper-V/Docker use 172.16/12). */
    private static int range(String host) {
        if (host.startsWith("192.168.")) return 0;
        if (host.startsWith("10.")) return 1;
        if (host.startsWith("172.")) {
            try {
                int second = Integer.parseInt(host.split("\\.")[1]);
                if (second >= 16 && second <= 31) return 3;
            } catch (RuntimeException ignored) {
            }
        }
        return 2;
    }

    /** Wi-Fi and Ethernet first; mobile data next; virtual adapters last. */
    private static int rank(String name) {
        if (name.startsWith("wireless") || name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("wi-fi")) return 0;
        if (name.startsWith("eth") || name.startsWith("en") || name.startsWith("ethernet")) return 1;
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) return 3;
        if (name.startsWith("vethernet") || name.startsWith("vmware") || name.startsWith("virtualbox")
                || name.startsWith("docker") || name.startsWith("br-") || name.startsWith("veth")
                || name.startsWith("tap") || name.startsWith("tun") || name.startsWith("utun")
                || name.startsWith("wg") || name.startsWith("zt")) return 9;
        return 2;
    }

    private record Candidate(int rank, int range, int local, String name, String host) {}
}
