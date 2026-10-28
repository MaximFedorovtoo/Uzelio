package org.uzel.app.messenger;

import android.content.Context;
import android.test.InstrumentationTestCase;
import org.uzel.app.NodeController;
import org.uzel.messenger.chat.ChatInvite;
import org.uzel.messenger.chat.ChatRepository;
import org.uzel.messenger.chat.ChatService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Reads stored conversations, invitation fingerprints and network peers, then tries to deliver. */
public final class ChatDeliveryProbeTest extends InstrumentationTestCase {
    public void testHistoryAndFreshDelivery() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Path ext = context.getExternalFilesDir(null).toPath();
        Path result = ext.resolve("delivery-result.txt");
        Files.deleteIfExists(result);
        ChatService chat = AndroidMessenger.chats(context).get(60, TimeUnit.SECONDS);
        StringBuilder out = new StringBuilder();
        out.append("peers:");
        for (NodeController.Peer peer : NodeController.get(context).snapshot().peers) {
            out.append('\n').append("  ").append(peer.id).append(" name=").append(peer.name)
                    .append(" reachable=").append(peer.reachable).append(" paired=").append(peer.paired);
        }
        out.append('\n');
        for (ChatRepository.Contact contact : chat.contacts()) {
            out.append("contact ").append(contact.name()).append('\n');
            try {
                ChatInvite invite = ChatInvite.decode(contact.inviteCode());
                out.append("  address=").append(invite.address()).append('\n');
                out.append("  fingerprint=").append(invite.tlsFingerprint()).append('\n');
                out.append("  relay=").append(invite.relay()).append('\n');
            } catch (Exception failed) {
                out.append("  invite-error=").append(failed).append('\n');
            }
            List<ChatRepository.Message> history = chat.history(contact.id());
            out.append("  history ").append(history.size()).append('\n');
            for (ChatRepository.Message message : history) {
                out.append("  - ").append(message.status()).append(" | ").append(message.text()).append('\n');
            }
        }
        if (!chat.contacts().isEmpty()) {
            ChatRepository.Contact contact = chat.contacts().get(0);
            chat.send(contact.id(), "Сеть работает");
            String status = "pending";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (System.nanoTime() < deadline) {
                for (ChatRepository.Message message : chat.history(contact.id())) {
                    if ("Сеть работает".equals(message.text())) { status = message.status(); break; }
                }
                if ("delivered".equals(status)) break;
                Thread.sleep(300);
            }
            out.append("freshDelivery=").append(status).append('\n');
        }
        Files.write(result, out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
