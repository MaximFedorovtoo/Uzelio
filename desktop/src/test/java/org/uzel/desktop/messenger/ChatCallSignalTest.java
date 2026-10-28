package org.uzel.desktop.messenger;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.uzel.core.*;
import org.uzel.desktop.IdentityStore;
import org.uzel.messenger.MessengerRuntime;
import org.uzel.messenger.chat.*;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.Assert.*;

/** Call signaling travels as an encrypted chat event and never becomes history. */
public final class ChatCallSignalTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void offerAnswerAndEndReachTheContactWithoutHistoryRows() throws Exception {
        try (Peer a = peer(); Peer b = peer()) {
            List<String> events = new CopyOnWriteArrayList<>();
            b.chat.listener(new ChatService.Listener() {
                public void incoming(String contactId, ChatRepository.Message message) {}
                public void call(String contactId, int kind, String callId, String sdp) {
                    events.add(kind + ":" + callId + ":" + sdp);
                }
            });
            String aid = a.chat.addContact(b.chat.invitation("127.0.0.1"), "Bob").id();
            String bid = b.chat.addContact(a.chat.invitation("127.0.0.1"), "Alice").id();

            a.chat.sendCall(aid, ChatWire.CALL_OFFER, "call-1", "v=0\r\ns=offer");
            await(() -> events.size() == 1);
            assertEquals("5:call-1:v=0\r\ns=offer", events.get(0));

            a.chat.sendCall(aid, ChatWire.CALL_ANSWER, "call-1", "v=0\r\ns=answer");
            a.chat.sendCall(aid, ChatWire.CALL_END, "call-1", "");
            await(() -> events.size() == 3);
            assertTrue(events.contains("6:call-1:v=0\r\ns=answer"));
            assertTrue(events.contains("7:call-1:"));

            assertTrue("signaling must not create chat history", a.chat.history(aid).isEmpty());
            assertTrue(b.chat.history(bid).isEmpty());
        }
    }

    @Test public void invalidCallIdIsRejectedLocally() throws Exception {
        try (Peer a = peer(); Peer b = peer()) {
            String aid = a.chat.addContact(b.chat.invitation("127.0.0.1"), "Bob").id();
            b.chat.addContact(a.chat.invitation("127.0.0.1"), "Alice");
            assertThrows(Exception.class, () -> a.chat.sendCall(aid, ChatWire.CALL_OFFER, "bad id!", "v=0"));
        }
    }

    private Peer peer() throws Exception {
        Path path = tmp.newFolder().toPath();
        MessengerRuntime runtime = DesktopMessenger.open(path);
        TlsIdentity identity = IdentityStore.load(path);
        NetworkAccess network = new NetworkAccess();
        ChatService chat = new ChatService(runtime, identity, network, InetAddress.getLoopbackAddress(), 0);
        return new Peer(runtime, chat);
    }

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
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!condition.get()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for call signaling");
            Thread.sleep(20);
        }
    }
}
