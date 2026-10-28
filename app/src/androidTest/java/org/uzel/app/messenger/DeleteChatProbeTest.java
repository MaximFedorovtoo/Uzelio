package org.uzel.app.messenger;

import android.content.Context;
import android.test.InstrumentationTestCase;
import org.uzel.messenger.chat.ChatRepository;
import org.uzel.messenger.chat.ChatService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deletes a stale conversation on the device and reports what remains. */
public final class DeleteChatProbeTest extends InstrumentationTestCase {
    public void testDeleteContactByName() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Path ext = context.getExternalFilesDir(null).toPath();
        Path result = ext.resolve("delete-result.txt");
        Path command = ext.resolve("delete-command.txt");
        Files.deleteIfExists(result);
        if (!Files.exists(command)) return;
        String name = new String(Files.readAllBytes(command), StandardCharsets.UTF_8).trim();
        ChatService chat = AndroidMessenger.chats(context).get(60, java.util.concurrent.TimeUnit.SECONDS);
        StringBuilder out = new StringBuilder();
        out.append("before:");
        for (ChatRepository.Contact contact : chat.contacts()) out.append(' ').append(contact.name());
        String target = null;
        for (ChatRepository.Contact contact : chat.contacts()) if (contact.name().equals(name)) target = contact.id();
        out.append('\n').append("target=").append(target).append('\n');
        if (target != null) chat.deleteContact(target);
        out.append("after:");
        for (ChatRepository.Contact contact : chat.contacts()) out.append(' ').append(contact.name());
        Files.write(result, out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
