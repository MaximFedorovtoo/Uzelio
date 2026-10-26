package org.uzel.desktop.messenger;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.uzel.core.*;
import org.uzel.desktop.IdentityStore;
import org.uzel.messenger.MessengerRuntime;
import org.uzel.messenger.chat.*;
import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/**
 * The direct address is unreachable; the message must travel over a P2P stream supplied by
 * the traversal provider, with the same pinned mTLS inside it.
 */
public final class ChatP2PTransportTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void messageTravelsOverP2PStreamWhenDirectAddressIsUnreachable() throws Exception {
        RoundTrip round = roundTrip();
        try (Peer a = round.a; Peer b = round.b) {
            String code = b.chat.invitation("direct.invalid");
            String aid = a.chat.addContact(code, "Bob").id();
            String bid = b.chat.addContact(a.chat.invitation("direct.invalid"), "Alice").id();

            a.chat.send(aid, "Через P2P-канал, без прямого адреса");
            await(() -> a.chat.history(aid).get(0).status().equals("delivered"));

            assertEquals("Через P2P-канал, без прямого адреса", b.chat.history(bid).get(0).text());
            assertTrue("the provider must be used for the fallback", round.streams.get() > 0);
            assertTrue("the delivered message must leave no pending outbox", a.runtime.chatRepository().pending().isEmpty());
        }
    }

    @Test public void providerWithoutLiveSessionKeepsMessageQueued() throws Exception {
        RoundTrip round = roundTrip();
        round.live.set(false);
        try (Peer a = round.a; Peer b = round.b) {
            String aid = a.chat.addContact(b.chat.invitation("direct.invalid"), "Bob").id();
            b.chat.addContact(a.chat.invitation("direct.invalid"), "Alice");
            a.chat.send(aid, "Нет ни прямого пути, ни P2P-сессии");
            Thread.sleep(1200);
            assertEquals("queued", a.chat.history(aid).get(0).status());
            assertTrue(b.runtime.chatRepository().history(b.chat.contacts().get(0).id()).isEmpty());
        }
    }

    private RoundTrip roundTrip() throws Exception {
        Path ar = tmp.newFolder().toPath(), br = tmp.newFolder().toPath();
        MessengerRuntime ra = DesktopMessenger.open(ar), rb = DesktopMessenger.open(br);
        TlsIdentity ia = IdentityStore.load(ar), ib = IdentityStore.load(br);
        NetworkAccess network = new NetworkAccess();
        ChatService b = new ChatService(rb, ib, network, InetAddress.getLoopbackAddress(), 0);
        int chatPort = b.localPort();
        AtomicInteger streams = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean live = new java.util.concurrent.atomic.AtomicBoolean(true);
        DesktopChatTransport.ChatStreams provider = fingerprint -> {
            if (!live.get()) return null;
            streams.incrementAndGet();
            return new Socket("127.0.0.1", chatPort);
        };
        ChatService a = new ChatService(ra, ia,
                (authorizer, receiver) -> new DesktopChatTransport(ia, ChatTransport.pinned(ia, network,
                        InetAddress.getLoopbackAddress(), 0, authorizer, receiver), provider), network);
        return new RoundTrip(new Peer(ra, a), new Peer(rb, b), streams, live);
    }

    private record RoundTrip(Peer a, Peer b, AtomicInteger streams, java.util.concurrent.atomic.AtomicBoolean live) {}

    private static final class Peer implements AutoCloseable {
        final MessengerRuntime runtime;
        final ChatService chat;

        Peer(MessengerRuntime runtime, ChatService chat) {
            this.runtime = runtime;
            this.chat = chat;
        }

        public void close() throws Exception {
            chat.close();
            runtime.close();
        }
    }

    private interface Condition { boolean get() throws Exception; }

    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.get()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for P2P delivery");
            Thread.sleep(25);
        }
    }
}
