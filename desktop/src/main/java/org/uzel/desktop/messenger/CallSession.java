package org.uzel.desktop.messenger;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import org.uzel.messenger.chat.ChatService;
import org.uzel.messenger.chat.ChatWire;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One peer-to-peer audio call between two Uzelio clients.
 *
 * <p>Signaling travels through {@link ChatService#sendCall} as encrypted chat events. SDP is held
 * only in memory for the duration of the negotiation; it is never written to history and never
 * logged. No microphone is opened for an incoming call until the user accepts it.</p>
 *
 * <p>All native WebRTC calls run on one daemon worker thread. {@link #close()} releases the
 * peer connection, the audio track and source, and the factory, and can be called repeatedly.</p>
 */
public final class CallSession implements AutoCloseable {
    public enum State { IDLE, CALLING, RINGING, CONNECTED, ENDED, FAILED }

    /** State and error callbacks arrive on the call worker, never on the UI thread. */
    public interface Listener {
        void state(CallSession source, State state, String contactId, String name);

        default void error(CallSession source, String message) {}
    }

    private static final Listener QUIET = new Listener() {
        public void state(CallSession source, State state, String contactId, String name) {}
    };
    private static final int MAX_SDP = 65536;
    private static final long GATHER_TIMEOUT_MILLIS = 15_000;
    private static final long CONNECT_TIMEOUT_MILLIS = 60_000;
    private static final String STREAM_ID = "uzelio-call";
    private static final String TRACK_ID = "uzelio-voice";
    private static final List<String> STUN_SERVERS = List.of(
            "stun:stun.cloudflare.com:3478",
            "stun:stun.l.google.com:19302");

    private final Listener listener;
    private final ScheduledExecutorService worker;
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String contactId = "", name = "", callId = "";
    private volatile String pendingOffer;
    private volatile boolean answered;
    private volatile ChatService service;
    private volatile ScheduledFuture<?> timeout;
    private PeerConnectionFactory factory;
    private RTCPeerConnection peer;
    private AudioTrack audioTrack;
    private AudioTrackSource audioSource;
    private boolean released;

    public CallSession(Listener listener) {
        this.listener = listener == null ? QUIET : listener;
        ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "uzelio-call");
            thread.setDaemon(true);
            return thread;
        });
        created.setRemoveOnCancelPolicy(true);
        worker = created;
    }

    /** Caller side: starts a fresh offer for an explicit contact selected by the user. */
    public void start(ChatService service, String contactId, String name) {
        submit(() -> doStart(service, contactId, name));
    }

    /** Incoming offer first becomes a visible ringing call; no audio device is opened yet. */
    public void incoming(ChatService service, String contactId, String name, String callId, String offerSdp) {
        submit(() -> doIncoming(service, contactId, name, callId, offerSdp));
    }

    /** Callee side: accepts the stored incoming offer and answers it. */
    public void accept(ChatService service, String contactId, String callId, String offerSdp) {
        submit(() -> doAccept(service, contactId, callId, offerSdp));
    }

    /** Declines a ringing call and notifies the caller with one encrypted CALL_END frame. */
    public void reject(String contactId) {
        submit(() -> doReject(contactId));
    }

    /** Ends an active call and notifies the contact with one encrypted CALL_END frame. */
    public void hangup() {
        submit(this::doHangup);
    }

    /** Routes a CALL_OFFER/CALL_ANSWER/CALL_END received by the chat listener. */
    public void handle(String contactId, int kind, String callId, String sdp) {
        submit(() -> doHandle(contactId, kind, callId, sdp));
    }

    public State state() { return state.get(); }

    public String contactId() { return contactId; }

    public String name() { return name; }

    /** Releases every native object and the worker thread; safe to call more than once. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cancelTimeout();
        try {
            worker.execute(this::release);
        } catch (RejectedExecutionException stopped) {
            release();
        }
        worker.shutdown();
    }

    private void doStart(ChatService source, String contact, String displayName) {
        if (closed.get()) return;
        State current = state.get();
        if (current != State.IDLE && current != State.ENDED && current != State.FAILED) return;
        if (source == null || blank(contact)) {
            fail("Контакт для звонка не выбран");
            return;
        }
        service = source;
        contactId = contact;
        name = safeName(displayName);
        callId = UUID.randomUUID().toString();
        pendingOffer = null;
        transition(State.CALLING);
        if (closed.get()) return;
        try {
            prepare();
            publishAudio();
            RTCSessionDescription description = newOffer();
            setLocal(description);
            if (!awaitGather()) throw new IOException("ICE gathering did not complete");
            String sdp = localSdp();
            if (!validSdp(sdp)) throw new IOException("Invalid local SDP");
            if (closed.get()) return;
            source.sendCall(contact, ChatWire.CALL_OFFER, callId, sdp);
            scheduleConnectTimeout("Абонент не отвечает");
        } catch (Exception failure) {
            fail("Не удалось начать звонок");
        }
    }

    private void doIncoming(ChatService source, String contact, String displayName, String id, String offerSdp) {
        if (closed.get()) return;
        State current = state.get();
        if (current == State.RINGING || current == State.CALLING || current == State.CONNECTED) return;
        if (source == null || blank(contact) || blank(id) || !validSdp(offerSdp)) {
            fail("Некорректные данные входящего звонка");
            return;
        }
        service = source;
        contactId = contact;
        name = safeName(displayName);
        callId = id;
        pendingOffer = offerSdp;
        answered = false;
        transition(State.RINGING);
        scheduleRingTimeout();
    }

    private void doAccept(ChatService source, String contact, String id, String offerSdp) {
        if (closed.get() || state.get() != State.RINGING) return;
        answered = true;
        cancelTimeout();
        String contactValue = blank(contact) ? contactId : contact;
        String callValue = blank(id) ? callId : id;
        String offerValue = blank(offerSdp) ? pendingOffer : offerSdp;
        if (source == null || blank(contactValue) || blank(callValue) || !validSdp(offerValue)) {
            fail("Некорректные данные входящего звонка");
            return;
        }
        service = source;
        contactId = contactValue;
        callId = callValue;
        pendingOffer = null;
        try {
            prepare();
            setRemote(new RTCSessionDescription(RTCSdpType.OFFER, offerValue));
            publishAudio();
            RTCSessionDescription description = newAnswer();
            setLocal(description);
            if (!awaitGather()) throw new IOException("ICE gathering did not complete");
            String sdp = localSdp();
            if (!validSdp(sdp)) throw new IOException("Invalid local SDP");
            if (closed.get()) return;
            source.sendCall(contactValue, ChatWire.CALL_ANSWER, callValue, sdp);
            scheduleConnectTimeout("Соединение не установлено");
        } catch (Exception failure) {
            fail("Не удалось принять звонок");
        }
    }

    private void doReject(String contact) {
        if (state.get() != State.RINGING) return;
        if (!blank(contact) && !blank(contactId) && !contact.equals(contactId)) return;
        transition(State.ENDED);
        sendEnd();
        finish();
    }

    private void doHangup() {
        State current = state.get();
        if (current != State.CALLING && current != State.RINGING && current != State.CONNECTED) return;
        transition(State.ENDED);
        sendEnd();
        finish();
    }

    private void doHandle(String contact, int kind, String id, String sdp) {
        if (closed.get()) return;
        if (kind == ChatWire.CALL_OFFER) {
            State current = state.get();
            if (current == State.IDLE || current == State.ENDED || current == State.FAILED) {
                doIncoming(service, contact, name, id, sdp);
            }
            return;
        }
        if (blank(contact) || blank(contactId) || !contactId.equals(contact)) return;
        if (blank(callId) || blank(id) || !callId.equals(id)) return;
        if (kind == ChatWire.CALL_ANSWER) {
            if (state.get() != State.CALLING) return;
            if (!validSdp(sdp)) {
                fail("Некорректный ответ собеседника");
                return;
            }
            try {
                setRemote(new RTCSessionDescription(RTCSdpType.ANSWER, sdp));
            } catch (Exception failure) {
                fail("Соединение не установлено");
            }
        } else if (kind == ChatWire.CALL_END) {
            State current = state.get();
            if (current == State.CALLING || current == State.RINGING || current == State.CONNECTED) {
                transition(State.ENDED);
                finish();
            }
        }
    }

    /** Creates the factory, peer connection, audio source and track; no negotiation yet. */
    private void prepare() throws Exception {
        PeerConnectionFactory created = new PeerConnectionFactory();
        factory = created;
        RTCConfiguration configuration = new RTCConfiguration();
        for (String url : STUN_SERVERS) {
            RTCIceServer server = new RTCIceServer();
            server.urls.add(url);
            configuration.iceServers.add(server);
        }
        configuration.continualGatheringPolicy = RTCContinualGatheringPolicy.GATHER_ONCE;
        RTCPeerConnection connection = created.createPeerConnection(configuration, new PeerObserver());
        if (connection == null) throw new IOException("Peer connection creation failed");
        peer = connection;
        AudioOptions options = new AudioOptions();
        options.echoCancellation = true;
        options.autoGainControl = true;
        options.noiseSuppression = true;
        options.highpassFilter = true;
        AudioTrackSource source = created.createAudioSource(options);
        if (source == null) throw new IOException("Audio source creation failed");
        audioSource = source;
        AudioTrack track = created.createAudioTrack(TRACK_ID, source);
        if (track == null) throw new IOException("Audio track creation failed");
        audioTrack = track;
    }

    /** Adds the microphone track; the caller does this before the offer, the callee after the remote offer. */
    private void publishAudio() throws Exception {
        RTCPeerConnection connection = peer;
        AudioTrack track = audioTrack;
        if (connection == null || track == null) throw new IOException("Audio track is not prepared");
        if (connection.addTrack(track, List.of(STREAM_ID)) == null) throw new IOException("Audio sender creation failed");
    }

    private RTCSessionDescription newOffer() throws Exception {
        RTCPeerConnection connection = peer;
        if (connection == null) throw new IOException("Peer connection is not prepared");
        CompletableFuture<RTCSessionDescription> done = new CompletableFuture<>();
        connection.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            public void onSuccess(RTCSessionDescription description) { done.complete(description); }
            public void onFailure(String error) { done.completeExceptionally(new IOException("Offer creation failed")); }
        });
        return done.get(10, TimeUnit.SECONDS);
    }

    private RTCSessionDescription newAnswer() throws Exception {
        RTCPeerConnection connection = peer;
        if (connection == null) throw new IOException("Peer connection is not prepared");
        CompletableFuture<RTCSessionDescription> done = new CompletableFuture<>();
        connection.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
            public void onSuccess(RTCSessionDescription description) { done.complete(description); }
            public void onFailure(String error) { done.completeExceptionally(new IOException("Answer creation failed")); }
        });
        return done.get(10, TimeUnit.SECONDS);
    }

    private void setLocal(RTCSessionDescription description) throws Exception {
        RTCPeerConnection connection = peer;
        if (connection == null) throw new IOException("Peer connection is not prepared");
        CompletableFuture<Void> done = new CompletableFuture<>();
        connection.setLocalDescription(description, new SetSessionDescriptionObserver() {
            public void onSuccess() { done.complete(null); }
            public void onFailure(String error) { done.completeExceptionally(new IOException("Local description failed")); }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    private void setRemote(RTCSessionDescription description) throws Exception {
        RTCPeerConnection connection = peer;
        if (connection == null) throw new IOException("Peer connection is not prepared");
        CompletableFuture<Void> done = new CompletableFuture<>();
        connection.setRemoteDescription(description, new SetSessionDescriptionObserver() {
            public void onSuccess() { done.complete(null); }
            public void onFailure(String error) { done.completeExceptionally(new IOException("Remote description failed")); }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    /** Waits for the single complete SDP frame used by the chat signaling protocol. */
    private boolean awaitGather() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GATHER_TIMEOUT_MILLIS);
        while (!closed.get() && System.nanoTime() < deadline) {
            RTCPeerConnection connection = peer;
            if (connection == null) return false;
            try {
                if (connection.getIceGatheringState() == RTCIceGatheringState.COMPLETE) return true;
            } catch (RuntimeException gone) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        RTCPeerConnection connection = peer;
        if (connection == null) return false;
        try {
            return connection.getIceGatheringState() == RTCIceGatheringState.COMPLETE;
        } catch (RuntimeException gone) {
            return false;
        }
    }

    private String localSdp() throws IOException {
        RTCPeerConnection connection = peer;
        if (connection == null) throw new IOException("Peer connection is not prepared");
        RTCSessionDescription local = connection.getLocalDescription();
        if (local == null || local.sdp == null) throw new IOException("Local description is not available");
        return local.sdp;
    }

    /** An unanswered incoming call stops ringing locally after the deadline; nothing is signaled. */
    private void scheduleRingTimeout() {
        cancelTimeout();
        try {
            timeout = worker.schedule(() -> submit(() -> {
                if (!answered && state.get() == State.RINGING) {
                    transition(State.ENDED);
                    finish();
                }
            }), CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            /* The session is closing; no timer is needed. */
        }
    }

    private void scheduleConnectTimeout(String reason) {
        cancelTimeout();
        try {
            timeout = worker.schedule(() -> submit(() -> {
                State current = state.get();
                if (current == State.CALLING || current == State.RINGING) fail(reason);
            }), CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            /* The session is closing; no timer is needed. */
        }
    }

    private void cancelTimeout() {
        ScheduledFuture<?> current = timeout;
        timeout = null;
        if (current != null) current.cancel(false);
    }

    private void transition(State next) {
        if (closed.get() && next != State.ENDED) return;
        State previous = state.getAndSet(next);
        if (previous == next) return;
        if (next == State.CONNECTED || next == State.ENDED || next == State.FAILED) cancelTimeout();
        notifyState(next);
    }

    private void fail(String message) {
        if (closed.get()) return;
        State current = state.get();
        if (current == State.ENDED || current == State.FAILED) return;
        state.set(State.FAILED);
        cancelTimeout();
        notifyState(State.FAILED);
        try {
            listener.error(this, message);
        } catch (RuntimeException ignored) {
            /* A failing listener must not keep native resources alive. */
        }
        finish();
    }

    /** Releases native objects and lets the daemon worker exit after a terminal state. */
    private void finish() {
        release();
        worker.shutdown();
    }

    private void notifyState(State next) {
        try {
            listener.state(this, next, contactId, name);
        } catch (RuntimeException ignored) {
            /* UI callbacks never change call state. */
        }
    }

    private void sendEnd() {
        ChatService source = service;
        String contact = contactId, id = callId;
        if (source == null || blank(contact) || blank(id)) return;
        try {
            source.sendCall(contact, ChatWire.CALL_END, id, "");
        } catch (Exception ignored) {
            /* Signaling is best effort, including an already closed chat service. */
        }
    }

    private void submit(Runnable action) {
        if (closed.get() || worker.isShutdown()) return;
        try {
            worker.execute(() -> {
                if (closed.get()) return;
                try {
                    action.run();
                } catch (Throwable failure) {
                    fail("Звонок не удался");
                }
            });
        } catch (RejectedExecutionException ignored) {
            /* A closing session discards late actions. */
        }
    }

    /** Disposes peer connection, track, source and factory exactly once. */
    private synchronized void release() {
        if (released) return;
        released = true;
        pendingOffer = null;
        RTCPeerConnection connection = peer;
        peer = null;
        if (connection != null) {
            try { connection.close(); } catch (RuntimeException | Error ignored) { /* Native cleanup is best effort. */ }
        }
        AudioTrack track = audioTrack;
        audioTrack = null;
        if (track != null) {
            try { track.dispose(); } catch (RuntimeException | Error ignored) { /* Native cleanup is best effort. */ }
        }
        AudioTrackSource source = audioSource;
        audioSource = null;
        if (source != null) {
            try { source.dispose(); } catch (RuntimeException | Error ignored) { /* Native cleanup is best effort. */ }
        }
        PeerConnectionFactory created = factory;
        factory = null;
        if (created != null) {
            try { created.dispose(); } catch (RuntimeException | Error ignored) { /* Native cleanup is best effort. */ }
        }
        service = null;
    }

    private static boolean validSdp(String sdp) {
        return sdp != null && sdp.length() <= MAX_SDP && sdp.startsWith("v=0");
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safeName(String value) {
        if (value == null || value.trim().isEmpty()) return "Контакт";
        String trimmed = value.trim();
        return trimmed.length() <= 100 ? trimmed : trimmed.substring(0, 100);
    }

    private final class PeerObserver implements PeerConnectionObserver {
        public void onIceCandidate(RTCIceCandidate candidate) {
            /* Candidates are delivered inside the single gathered SDP frame. */
        }

        public void onConnectionChange(RTCPeerConnectionState value) {
            if (closed.get()) return;
            if (value == RTCPeerConnectionState.CONNECTED) {
                submit(() -> {
                    State current = state.get();
                    if (current == State.CALLING || current == State.RINGING) {
                        transition(State.CONNECTED);
                    }
                });
            } else if (value == RTCPeerConnectionState.FAILED) {
                submit(() -> {
                    State current = state.get();
                    if (current == State.CALLING || current == State.RINGING || current == State.CONNECTED) {
                        fail("Соединение прервано");
                    }
                });
            }
        }
    }
}
