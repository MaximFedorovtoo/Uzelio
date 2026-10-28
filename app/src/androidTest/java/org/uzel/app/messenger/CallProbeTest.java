package org.uzel.app.messenger;

import android.content.Context;
import android.test.InstrumentationTestCase;
import org.uzel.messenger.chat.ChatRepository;
import org.uzel.messenger.chat.ChatService;
import org.uzel.messenger.chat.ChatWire;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Places or answers a real audio call without any UI, so the media path can be verified
 * automatically: the callee accepts as soon as it rings, both sides report the final state.
 */
public final class CallProbeTest extends InstrumentationTestCase {
    public void testCallee() throws Exception { run(false); }
    public void testCaller() throws Exception { run(true); }

    private void run(boolean caller) throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Path ext = context.getExternalFilesDir(null).toPath();
        Path result = ext.resolve(caller ? "call-caller.txt" : "call-callee.txt");
        Files.deleteIfExists(result);
        ChatService service = AndroidMessenger.chats(context).get(60, TimeUnit.SECONDS);
        List<ChatRepository.Contact> contacts = service.contacts();
        final String contact = contacts.isEmpty() ? null : contacts.get(0).id();
        if (contact == null) {
            Files.write(result, "no chat contact".getBytes(StandardCharsets.UTF_8));
            return;
        }
        List<String> states = new CopyOnWriteArrayList<>();
        final CallSession[] session = new CallSession[1];
        CallSession.Listener listener = new CallSession.Listener() {
            @Override public void onCallState(CallSession call, CallSession.State state) {
                states.add(state.name());
                if (!caller && state == CallSession.State.RINGING) call.accept();
            }
        };
        session[0] = new CallSession(context, service, listener);
        AndroidMessenger.setCallHandler((contactId, kind, callId, sdp) -> {
            CallSession call = session[0];
            if (call == null) return;
            if (kind == ChatWire.CALL_OFFER) call.incoming(contactId, callId, sdp);
            else if (kind == ChatWire.CALL_ANSWER) call.answer(callId, sdp);
            else if (kind == ChatWire.CALL_END) call.end(callId);
        });
        if (caller) session[0].start(contact);
        boolean connected = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100);
        while (System.nanoTime() < deadline) {
            if (session[0].state() == CallSession.State.CONNECTED) { connected = true; break; }
            if (session[0].state() == CallSession.State.FAILED || session[0].state() == CallSession.State.ENDED) break;
            Thread.sleep(200);
        }
        Files.write(result, ("caller=" + caller + "\nconnected=" + connected + "\nstates=" + states
                + "\nfinal=" + session[0].state()).getBytes(StandardCharsets.UTF_8));
        session[0].hangup();
        AndroidMessenger.setCallHandler(null);
    }
}
