package org.uzel.desktop.messenger;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.uzel.core.*;
import org.uzel.desktop.IdentityStore;
import org.uzel.messenger.MessengerRuntime;
import org.uzel.messenger.chat.*;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import static org.junit.Assert.*;

/** A file travels as metadata plus chunks and reassembles only after the last one. */
public final class ChatFileTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void fileArrivesIntactAndIsReportedDelivered() throws Exception {
        try (Peer a = peer(); Peer b = peer()) {
            String aid = a.chat.addContact(b.chat.invitation("127.0.0.1"), "Bob").id();
            String bid = b.chat.addContact(a.chat.invitation("127.0.0.1"), "Alice").id();
            byte[] content = new byte[300 * 1024];
            new Random(5).nextBytes(content);

            a.chat.sendFile(aid, "photo.jpg", "image/jpeg", content);
            try {
                await(() -> a.chat.history(aid).get(0).status().equals("delivered"));
            } catch (AssertionError timeout) {
                StringBuilder dump = new StringBuilder();
                dump.append("A history: ").append(a.chat.history(aid)).append('\n');
                dump.append("B history: ").append(b.chat.history(bid)).append('\n');
                dump.append("A pending: ").append(a.runtime.chatRepository().pending()).append('\n');
                java.nio.file.Files.writeString(java.nio.file.Path.of("C:\\Users\\Public\\uzel-build-temp\\chat-file-debug.txt"), dump);
                throw timeout;
            }

            ChatRepository.Message received = b.chat.history(bid).get(0);
            assertNotNull("attachment metadata must be stored", received.attachment());
            assertEquals("photo.jpg", received.attachment().name());
            assertEquals("image/jpeg", received.attachment().mime());
            assertEquals(content.length, received.attachment().size());
            assertArrayEquals(content, b.runtime.chatRepository().blob(received.id()));
            assertArrayEquals(content, a.runtime.chatRepository().blob(a.chat.history(aid).get(0).id()));
        }
    }

    @Test public void oversizedFileIsRejectedBeforeAnyFrameIsQueued() throws Exception {
        try (Peer a = peer(); Peer b = peer()) {
            String aid = a.chat.addContact(b.chat.invitation("127.0.0.1"), "Bob").id();
            b.chat.addContact(a.chat.invitation("127.0.0.1"), "Alice");
            assertThrows(IllegalArgumentException.class,
                    () -> a.chat.sendFile(aid, "big.bin", "application/octet-stream", new byte[8 * 1024 * 1024 + 1]));
            assertTrue(a.chat.history(aid).isEmpty());
            assertTrue(a.runtime.chatRepository().pending().isEmpty());
        }
    }

    @Test public void emptyAndUnnamedFilesAreRejected() throws Exception {
        try (Peer a = peer(); Peer b = peer()) {
            String aid = a.chat.addContact(b.chat.invitation("127.0.0.1"), "Bob").id();
            b.chat.addContact(a.chat.invitation("127.0.0.1"), "Alice");
            assertThrows(IllegalArgumentException.class, () -> a.chat.sendFile(aid, "", "text/plain", new byte[]{1}));
            assertThrows(IllegalArgumentException.class, () -> a.chat.sendFile(aid, "x.txt", "not-a-mime", new byte[]{1}));
            assertThrows(IllegalArgumentException.class, () -> a.chat.sendFile(aid, "x.txt", "text/plain", new byte[0]));
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
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!condition.get()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for the file transfer");
            Thread.sleep(25);
        }
    }
}
