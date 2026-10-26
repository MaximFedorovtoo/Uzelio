package org.uzel.desktop.messenger;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.uzel.core.*;
import org.uzel.desktop.IdentityStore;
import org.uzel.messenger.MessengerRuntime;
import org.uzel.messenger.chat.*;
import org.uzel.messenger.relay.*;
import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import static org.junit.Assert.*;

/** Cross-network chat: the direct path is blocked, only the relay is reachable. */
public final class ChatRelayTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void twoPeersWithoutDirectPathExchangeTextAndReceiptThroughRelay() throws Exception {
        try (RelayServer relay = relay()) {
            RelayEndpoint endpoint = new RelayEndpoint("127.0.0.1", relay.port(), relay.pin());
            try (Peer a = peer(relay.port()); Peer b = peer(relay.port())) {
                String aid = a.chat.addContact(b.chat.invitation("127.0.0.1", endpoint.encode()), "Bob").id();
                String bid = b.chat.addContact(a.chat.invitation("127.0.0.1", endpoint.encode()), "Alice").id();
                a.chat.send(aid, "Через relay, без прямого пути");
                await(() -> a.chat.history(aid).get(0).status().equals("delivered"));
                assertEquals("Через relay, без прямого пути", b.chat.history(bid).get(0).text());
                assertTrue("the delivered message must leave no pending outbox", a.runtime.chatRepository().pending().isEmpty());
                assertEquals(1, b.chat.history(bid).size());
                a.chat.retryNow();
                assertEquals("a repeated sweep must not duplicate history", 1, b.chat.history(bid).size());
            }
        }
    }

    @Test public void offlineRecipientKeepsSenderQueuedUntilItsRelayInboxReturns() throws Exception {
        try (RelayServer relay = relay()) {
            RelayEndpoint endpoint = new RelayEndpoint("127.0.0.1", relay.port(), relay.pin());
            Peer a = peer(relay.port());
            Peer b = peer(relay.port());
            String aid, bid;
            try {
                aid = a.chat.addContact(b.chat.invitation("127.0.0.1", endpoint.encode()), "Bob").id();
                bid = b.chat.addContact(a.chat.invitation("127.0.0.1", endpoint.encode()), "Alice").id();
            } catch (Exception failure) {
                a.close(); b.close(); throw failure;
            }
            int port = b.chat.localPort();
            b.chat.close();
            a.chat.send(aid, "Пока получатель офлайн");
            Thread.sleep(1200);
            assertEquals("queued", a.chat.history(aid).get(0).status());
            assertTrue(b.runtime.chatRepository().history(bid).isEmpty());
            try (ChatService restored = new ChatService(b.runtime, b.identity,
                    (auth, receiver) -> ChatTransport.pinned(b.identity, b.network, InetAddress.getLoopbackAddress(), port, auth, receiver), b.network)) {
                a.chat.retryNow();
                await(() -> a.chat.history(aid).get(0).status().equals("delivered"));
                assertEquals("Пока получатель офлайн", restored.history(bid).get(0).text());
            } finally {
                a.close(); b.runtime.close();
            }
        }
    }

    private RelayServer relay() throws Exception {
        return new RelayServer(IdentityStore.load(tmp.newFolder().toPath()), InetAddress.getLoopbackAddress(), 0);
    }

    private Peer peer(int relayPort) throws Exception {
        Path path = tmp.newFolder().toPath();
        MessengerRuntime runtime = DesktopMessenger.open(path);
        TlsIdentity identity = IdentityStore.load(path);
        NetworkAccess blocked = new NetworkAccess() {
            @Override public InetAddress[] resolve(String host) throws IOException {
                return super.resolve(host);
            }
            @Override public Socket open(String host, int port) throws IOException {
                if (port != relayPort) throw new IOException("Direct path is blocked in this test");
                return super.open(host, port);
            }
            @Override public Socket open(InetAddress address, int port) throws IOException {
                if (port != relayPort) throw new IOException("Direct path is blocked in this test");
                return super.open(address, port);
            }
        };
        ChatService chat = new ChatService(runtime, identity,
                (auth, receiver) -> ChatTransport.pinned(identity, blocked, InetAddress.getLoopbackAddress(), 0, auth, receiver), blocked);
        return new Peer(runtime, chat, identity, blocked);
    }

    private static final class Peer implements AutoCloseable {
        final MessengerRuntime runtime; final ChatService chat; final TlsIdentity identity; final NetworkAccess network;
        Peer(MessengerRuntime runtime, ChatService chat, TlsIdentity identity, NetworkAccess network) {
            this.runtime = runtime; this.chat = chat; this.identity = identity; this.network = network;
        }
        public void close() throws Exception {
            chat.close(); runtime.close();
        }
    }

    private interface Condition { boolean get() throws Exception; }

    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.get()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for relay delivery");
            Thread.sleep(25);
        }
    }
}
