package org.uzel.desktop.messenger;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.uzel.core.*;
import org.uzel.desktop.IdentityStore;
import org.uzel.messenger.MessengerRuntime;
import org.uzel.messenger.chat.*;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Random;
import static org.junit.Assert.*;

/** Deleting a chat removes the contact, its history, queued frames and stored files. */
public final class ChatDeleteTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void deletingAConversationLeavesNoTraceButKeepsTheOtherSide() throws Exception {
        try (Peer a = peer(); Peer b = peer()) {
            String aid = a.chat.addContact(b.chat.invitation("127.0.0.1"), "Bob").id();
            String bid = b.chat.addContact(a.chat.invitation("127.0.0.1"), "Alice").id();
            a.chat.send(aid, "привет");
            await(() -> b.chat.history(bid).size() == 1);
            byte[] content = new byte[16 * 1024];
            new Random(11).nextBytes(content);
            a.chat.sendFile(aid, "note.bin", "application/octet-stream", content);
            await(() -> b.chat.history(bid).size() == 2);
            ChatRepository.Message file = b.chat.history(bid).get(1);
            assertNotNull(b.chat.attachment(file.id()));

            b.chat.deleteContact(bid);

            assertTrue("contact must be gone", b.chat.contacts().isEmpty());
            assertThrows("history lookup must fail for a removed contact", IllegalArgumentException.class, () -> b.chat.history(bid));
            assertNull("stored file must be gone", b.chat.attachment(file.id()));
            assertTrue("no queued frames may remain", b.runtime.chatRepository().pending().isEmpty());
            assertEquals("the other side keeps its history", 2, a.chat.history(aid).size());
        }
    }

    private Peer peer() throws Exception {
        Path path = tmp.newFolder().toPath();
        MessengerRuntime runtime = DesktopMessenger.open(path);
        TlsIdentity identity = IdentityStore.load(path);
        ChatService chat = new ChatService(runtime, identity, new NetworkAccess(), InetAddress.getLoopbackAddress(), 0);
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
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.get()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for delivery");
            Thread.sleep(20);
        }
    }
}
