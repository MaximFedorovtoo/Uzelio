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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/**
 * Counterpart of the on-device probe. Runs only when UZELIO_PHONE_INVITE is set:
 * it publishes its own invitation, accepts the phone's contact, replies and receives
 * the file the phone sends. Normal test runs are unaffected.
 */
public final class LiveLanProbeTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test public void talkToPhoneOverLan() throws Exception {
        String peerPath = System.getenv("UZELIO_PHONE_INVITE");
        if (peerPath == null || peerPath.isBlank()) return;
        String outPath = System.getenv().getOrDefault("UZELIO_OUT_INVITE",
                Path.of(System.getProperty("java.io.tmpdir"), "uzelio-pc-invite.txt").toString());
        String bind = System.getenv().getOrDefault("UZELIO_BIND", "192.168.1.36");

        Path dir = tmp.newFolder().toPath();
        MessengerRuntime runtime = DesktopMessenger.open(dir);
        TlsIdentity identity = IdentityStore.load(dir);
        ChatService chat = new ChatService(runtime, identity, new NetworkAccess(), InetAddress.getByName(bind), 0);
        try {
            Path out = Path.of(outPath);
            String relay = System.getenv().getOrDefault("UZELIO_RELAY", "");
            Files.writeString(out.resolveSibling(out.getFileName() + ".tmp"), chat.invitation(bind, relay), StandardCharsets.UTF_8);
            Files.move(out.resolveSibling(out.getFileName() + ".tmp"), out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            String code = Files.readString(Path.of(peerPath), StandardCharsets.UTF_8).trim();
            ChatRepository.Contact contact = chat.addContact(code, "OnePlus");
            List<String> events = new CopyOnWriteArrayList<>();
            chat.listener(new ChatService.Listener() {
                public void incoming(String contactId, ChatRepository.Message message) { events.add(message.text()); }
            });

            await(() -> !events.isEmpty(), "message from the phone", 240);
            assertEquals("Привет с OnePlus", events.get(0));

            chat.send(contact.id(), "Ответ с ПК");
            await(() -> chat.history(contact.id()).stream().anyMatch(m -> "Ответ с ПК".equals(m.text()) && "delivered".equals(m.status())), "reply delivery", 240);
            await(() -> chat.history(contact.id()).stream().anyMatch(m -> m.attachment() != null), "file from the phone", 240);

            ChatRepository.Message fileMessage = chat.history(contact.id()).stream().filter(m -> m.attachment() != null).findFirst().orElseThrow();
            byte[] blob = chat.attachment(fileMessage.id());
            String result = "received=" + events.get(0)
                    + "\nfile=" + fileMessage.attachment().name() + ":" + fileMessage.attachment().size()
                    + "\nblob=" + (blob == null ? -1 : blob.length)
                    + "\nreplyStatus=" + chat.history(contact.id()).stream().filter(m -> "Ответ с ПК".equals(m.text())).findFirst().map(ChatRepository.Message::status).orElse("absent");
            Files.writeString(out.resolveSibling("pc-result.txt"), result, StandardCharsets.UTF_8);
        } finally {
            chat.close();
            runtime.close();
        }
    }

    private interface Condition { boolean get() throws Exception; }

    private static void await(Condition condition, String what, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.get()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for " + what);
            Thread.sleep(100);
        }
    }
}
