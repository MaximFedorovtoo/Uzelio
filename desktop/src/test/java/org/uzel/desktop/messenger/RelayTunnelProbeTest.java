package org.uzel.desktop.messenger;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.uzel.core.NetworkAccess;
import org.uzel.core.TlsIdentity;
import org.uzel.desktop.IdentityStore;
import org.uzel.messenger.MessengerRuntime;
import org.uzel.messenger.chat.ChatRepository;
import org.uzel.messenger.chat.ChatService;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/**
 * Two peers that cannot reach each other directly: both advertise an unroutable
 * address and are joined only through the public relay given by UZELIO_RELAY
 * ("host:port:pin"). Verifies the relay path itself, not just loopback delivery.
 */
public final class RelayTunnelProbeTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void peersMeetThroughThePublicRelay() throws Exception {
        String relay = System.getenv("UZELIO_RELAY");
        if (relay == null || relay.isBlank()) return;
        Path root = tmp.newFolder().toPath();
        MessengerRuntime runtimeA = DesktopMessenger.open(root.resolve("a"));
        MessengerRuntime runtimeB = DesktopMessenger.open(root.resolve("b"));
        TlsIdentity identityA = IdentityStore.load(root.resolve("a"));
        TlsIdentity identityB = IdentityStore.load(root.resolve("b"));
        NetworkAccess network = new NetworkAccess();
        ChatService a = new ChatService(runtimeA, identityA, network, InetAddress.getLoopbackAddress(), 0);
        ChatService b = new ChatService(runtimeB, identityB, network, InetAddress.getLoopbackAddress(), 0);
        try {
            String aid = a.addContact(b.invitation("10.255.255.1", relay), "B").id();
            String bid = b.addContact(a.invitation("10.255.255.1", relay), "A").id();
            a.send(aid, "через relay");
            await(() -> !b.history(bid).isEmpty(), 180);
            assertEquals("через relay", b.history(bid).get(0).text());
            b.send(bid, "ответ через relay");
            await(() -> a.history(aid).size() == 2, 180);
            assertEquals("ответ через relay", a.history(aid).get(1).text());
            await(() -> a.history(aid).get(0).status().equals("delivered"), 120);
        } finally {
            a.close();
            b.close();
            runtimeA.close();
            runtimeB.close();
        }
    }

    private interface Condition { boolean get() throws Exception; }

    private static void await(Condition condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.get()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for relay delivery");
            Thread.sleep(100);
        }
    }
}
