package org.uzel.app.messenger;

import android.app.NotificationManager;
import android.content.Context;
import android.test.InstrumentationTestCase;
import org.uzel.app.DeviceIdentity;
import org.uzel.core.NetworkAccess;
import org.uzel.core.TlsIdentity;
import org.uzel.messenger.MessengerRuntime;
import org.uzel.messenger.chat.ChatRepository;
import org.uzel.messenger.chat.ChatService;
import org.uzel.messenger.chat.LocalAddress;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Live probe: the phone becomes a real chat peer on the local Wi-Fi and talks to the
 * desktop, which is started separately. Exchanges invitation codes through the app's
 * external files directory so adb can carry them between the two machines.
 */
public final class LanChatProbeTest extends InstrumentationTestCase {
    public void testPairWithDesktopOverLan() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Path ext = context.getExternalFilesDir(null).toPath();
        Path inviteFile = ext.resolve("lan-invite.txt");
        Path peerFile = ext.resolve("lan-peer.txt");
        Path resultFile = ext.resolve("lan-result.txt");
        for (Path p : new Path[]{inviteFile, peerFile, resultFile}) Files.deleteIfExists(p);

        Path root = Files.createTempDirectory(context.getNoBackupFilesDir().toPath(), "lan-probe-");
        TlsIdentity identity = DeviceIdentity.load();
        var protector = new AndroidKeyProtector();
        try (MessengerRuntime runtime = MessengerRuntime.open(root, protector, (p, k) -> new AndroidMessageStore(p.toFile(), k))) {
            String host = LocalAddress.preferred();
            ChatService chat = new ChatService(runtime, identity, new NetworkAccess(), InetAddress.getByName(host), 0);
            try {
                Files.write(inviteFile, chat.invitation(host).getBytes(StandardCharsets.UTF_8));
                await(() -> Files.exists(peerFile), "desktop invitation");
                String peer = new String(Files.readAllBytes(peerFile), StandardCharsets.UTF_8).trim();

                ChatRepository.Contact contact = chat.addContact(peer, "PC");
                List<String> events = new CopyOnWriteArrayList<>();
                chat.listener(new ChatService.Listener() {
                    public void incoming(String contactId, ChatRepository.Message message) { events.add(message.text()); }
                    public void call(String contactId, int kind, String callId, String sdp) {}
                });

                chat.send(contact.id(), "Привет с OnePlus");
                String sentId = chat.history(contact.id()).get(0).id();
                await(() -> chat.history(contact.id()).stream().anyMatch(m -> m.text().equals("Привет с OnePlus") && m.status().equals("delivered")), "delivery receipt");
                await(() -> !events.isEmpty(), "desktop reply");

                ChatNotifications.ensure(context);
                ChatNotifications.show(context, "PC", events.get(0));
                NotificationManager manager = context.getSystemService(NotificationManager.class);
                boolean notified = false;
                for (int attempt = 0; attempt < 40 && !notified; attempt++) {
                    for (var active : manager.getActiveNotifications()) {
                        if ("org.uzel.app".equals(active.getPackageName()) && ChatNotifications.CHANNEL.equals(active.getNotification().getChannelId())) notified = true;
                    }
                    if (!notified) Thread.sleep(50);
                }
                manager.cancel(39283);

                byte[] data = new byte[32 * 1024];
                new Random(9).nextBytes(data);
                chat.sendFile(contact.id(), "with-love.txt", "text/plain", data);
                await(() -> chat.history(contact.id()).stream().anyMatch(m -> m.attachment() != null
                        && "delivered".equals(m.status())), "file delivery");

                Files.write(resultFile, ("reply=" + events.get(0)
                        + "\nmessageId=" + sentId
                        + "\nnotification=" + notified
                        + "\nsoHost=" + host).getBytes(StandardCharsets.UTF_8));
            } finally {
                chat.close();
            }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);
            }
        }
    }

    private interface Condition { boolean get() throws Exception; }

    private static void await(Condition condition, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
        while (!condition.get()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for " + what);
            Thread.sleep(100);
        }
    }
}
