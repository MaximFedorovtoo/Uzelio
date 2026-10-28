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

/** The notification hook fires once per new message and never for receipts or duplicates. */
public final class ChatNotificationTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void incomingMessageFiresOneListenerAndRetransmissionDoesNotRepeat() throws Exception {
        try (Peer a = peer(); Peer b = peer()) {
            List<String> events = new CopyOnWriteArrayList<>();
            b.chat.listener((contactId, message) -> events.add(message.text()));
            String aid = a.chat.addContact(b.chat.invitation("127.0.0.1"), "Bob").id();
            String bid = b.chat.addContact(a.chat.invitation("127.0.0.1"), "Alice").id();

            a.chat.send(aid, "Уведомление о сообщении");
            await(() -> a.chat.history(aid).get(0).status().equals("delivered"));
            assertEquals("exactly one notification per stored message", List.of("Уведомление о сообщении"), events);

            // The same ciphertext retried after a restart is a duplicate: it must not notify again.
            a.chat.retryNow();
            Thread.sleep(600);
            assertEquals(1, events.size());
            assertEquals("Уведомление о сообщении", b.chat.history(bid).get(0).text());
            assertEquals("Alice", b.chat.contactName(bid));
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
            if (System.nanoTime() > deadline) fail("Timed out waiting for delivery");
            Thread.sleep(20);
        }
    }
}
