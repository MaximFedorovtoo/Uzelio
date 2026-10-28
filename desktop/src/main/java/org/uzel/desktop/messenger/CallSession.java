package org.uzel.desktop.messenger;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import org.uzel.messenger.chat.ChatService;
import org.uzel.messenger.chat.ChatWire;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One peer-to-peer audio or video call between two Uzelio clients.
 *
 * <p>Signaling travels through {@link ChatService#sendCall} as encrypted chat events. SDP is held
 * only in memory for the duration of the negotiation; it is never written to history and never
 * logged. No microphone or camera is opened for an incoming call until the user accepts it.</p>
 *
 * <p>Video is optional in both directions. A caller asks for it with {@link #startVideo}; a callee
 * adds a camera track only when the remote offer contains an {@code m=video} section. If no camera
 * is available the call continues with audio and the listener receives a displayable notice.</p>
 *
 * <p>All native WebRTC calls run on one daemon worker thread. Frame conversion runs on a separate
 * daemon thread so a slow UI can never stall signaling. Frames are never logged. {@link #close()}
 * releases the camera source, sinks, tracks, peer connection and factory, and can be called
 * repeatedly.</p>
 */
public final class CallSession implements AutoCloseable {
    public enum State { IDLE, CALLING, RINGING, CONNECTED, ENDED, FAILED }

    /** State and error callbacks arrive on the call worker, never on the UI thread. */
    public interface Listener {
        void state(CallSession source, State state, String contactId, String name);

        default void error(CallSession source, String message) {}

        /**
         * A decoded camera or remote frame, ready to paint. Called on the frame worker, never on
         * the native WebRTC thread, and never on the UI thread.
         */
        default void videoFrame(CallSession source, BufferedImage image, boolean local) {}

        /** Human-readable video status (camera missing, busy or silent); called on the call worker. */
        default void videoNotice(CallSession source, String message) {}
    }

    private static final Listener QUIET = new Listener() {
        public void state(CallSession source, State state, String contactId, String name) {}
    };
    private static final int MAX_SDP = 65536;
    private static final long GATHER_TIMEOUT_MILLIS = 15_000;
    private static final long CONNECT_TIMEOUT_MILLIS = 60_000;
    private static final long VIDEO_WATCHDOG_MILLIS = 4_000;
    private static final long REMOTE_FRAME_INTERVAL_NANOS = 66_000_000L;
    private static final long PREVIEW_FRAME_INTERVAL_NANOS = 100_000_000L;
    private static final int MAX_FRAME_WIDTH = 960;
    private static final int MAX_FRAME_HEIGHT = 540;
    private static final String STREAM_ID = "uzelio-call";
    private static final String TRACK_ID = "uzelio-voice";
    private static final String VIDEO_TRACK_ID = "uzelio-video";
    private static final List<String> STUN_SERVERS = List.of(
            "stun:stun.cloudflare.com:3478",
            "stun:stun.l.google.com:19302");

    private final Listener listener;
    private final ScheduledExecutorService worker;
    private final ExecutorService videoWorker;
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean remoteFrameBusy = new AtomicBoolean();
    private final AtomicBoolean localFrameBusy = new AtomicBoolean();
    private volatile String contactId = "", name = "", callId = "";
    private volatile String pendingOffer;
    private volatile boolean answered;
    private volatile ChatService service;
    private volatile ScheduledFuture<?> timeout;
    private PeerConnectionFactory factory;
    private RTCPeerConnection peer;
    private AudioTrack audioTrack;
    private AudioTrackSource audioSource;
    private VideoDeviceSource videoSource;
    private VideoTrack videoTrack;
    private VideoTrackSink localSink;
    private VideoTrackSink remoteSink;
    private VideoTrack remoteVideoTrack;
    private volatile boolean videoWanted;
    private volatile boolean videoPublished;
    private volatile boolean videoReleased;
    private volatile boolean localFrameSeen;
    private volatile long remoteFrameAt;
    private volatile long localFrameAt;
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
        videoWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "uzelio-call-video");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Caller side: starts a fresh audio-only offer for an explicit contact selected by the user. */
    public void start(ChatService service, String contactId, String name) {
        submit(() -> doStart(service, contactId, name, false));
    }

    /** Caller side: starts an offer with camera video when the machine has a usable camera. */
    public void startVideo(ChatService service, String contactId, String name) {
        submit(() -> doStart(service, contactId, name, true));
    }

    /** Incoming offer first becomes a visible ringing call; no audio or video device is opened yet. */
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

    /** True when an SDP offer or answer contains a video media section. */
    public static boolean hasVideoStream(String sdp) {
        if (sdp == null) return false;
        for (String line : sdp.split("\r?\n")) {
            if (line.startsWith("m=video")) return true;
        }
        return false;
    }

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

    private void doStart(ChatService source, String contact, String displayName, boolean withVideo) {
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
        videoWanted = withVideo;
        transition(State.CALLING);
        if (closed.get()) return;
        try {
            prepare();
            publishAudio();
            if (withVideo) {
                if (publishVideo()) {
                    scheduleVideoWatchdog();
                } else {
                    notifyVideo("Камера недоступна — звонок продолжается без видео");
                }
            }
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
            if (hasVideoStream(offerValue)) {
                videoWanted = true;
                if (publishVideo()) {
                    scheduleVideoWatchdog();
                } else {
                    notifyVideo("Камера недоступна — вас не видно, звонок продолжается");
                }
                attachRemoteVideo();
            }
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
                if (videoWanted) attachRemoteVideo();
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

    /**
     * Opens the first camera, adds a preview sink and publishes the video track. Returns false and
     * leaves the call audio-only when no camera exists, the device is busy, or native video fails.
     */
    private boolean publishVideo() {
        if (videoTrack != null) return true;
        RTCPeerConnection connection = peer;
        PeerConnectionFactory created = factory;
        if (connection == null || created == null) return false;
        try {
            VideoDevice device = firstCamera();
            if (device == null) return false;
            VideoDeviceSource source = new VideoDeviceSource();
            videoSource = source;
            source.setVideoCaptureDevice(device);
            VideoCaptureCapability capability = preferredCapability(device);
            if (capability != null) source.setVideoCaptureCapability(capability);
            source.start();
            VideoTrack track = created.createVideoTrack(VIDEO_TRACK_ID, source);
            if (track == null) throw new IOException("Video track creation failed");
            videoTrack = track;
            LocalVideoSink preview = new LocalVideoSink();
            try {
                track.addSink(preview);
                localSink = preview;
            } catch (RuntimeException | LinkageError ignored) {
                /* Sending video still matters; the local preview is optional. */
            }
            if (connection.addTrack(track, List.of(STREAM_ID)) == null) {
                throw new IOException("Video sender creation failed");
            }
            videoPublished = true;
            return true;
        } catch (Exception | LinkageError failure) {
            releaseVideoInput();
            return false;
        }
    }

    private static VideoDevice firstCamera() {
        List<VideoDevice> devices = MediaDevices.getVideoCaptureDevices();
        return devices == null || devices.isEmpty() ? null : devices.get(0);
    }

    /** Chooses a modest capture format: close to 640x480, at most 720p, and at most 30 fps. */
    private static VideoCaptureCapability preferredCapability(VideoDevice device) {
        List<VideoCaptureCapability> capabilities = MediaDevices.getVideoCaptureCapabilities(device);
        if (capabilities == null || capabilities.isEmpty()) return null;
        VideoCaptureCapability best = null;
        long bestCost = Long.MAX_VALUE;
        for (VideoCaptureCapability capability : capabilities) {
            if (capability == null || capability.width <= 0 || capability.height <= 0) continue;
            long cost = Math.abs(capability.width - 640) * 2L + Math.abs(capability.height - 480) * 2L;
            if (capability.width < 320 || capability.height < 240) cost += 5000;
            if ((long) capability.width * capability.height > 1280L * 720L) cost += 2000;
            cost += Math.max(0, 30 - capability.frameRate) * 20L;
            if (cost < bestCost) {
                bestCost = cost;
                best = capability;
            }
        }
        return best;
    }

    /** Stops and disposes the local camera, preview sink and video track exactly once. */
    private synchronized void releaseVideoInput() {
        VideoDeviceSource source = videoSource;
        videoSource = null;
        VideoTrack track = videoTrack;
        videoTrack = null;
        VideoTrackSink sink = localSink;
        localSink = null;
        if (source != null) {
            try { source.stop(); } catch (RuntimeException | Error ignored) { /* Native cleanup is best effort. */ }
        }
        if (track != null) {
            if (sink != null) {
                try { track.removeSink(sink); } catch (RuntimeException | Error ignored) { /* Native cleanup is best effort. */ }
            }
            try { track.dispose(); } catch (RuntimeException | Error ignored) { /* Native cleanup is best effort. */ }
        }
        if (source != null) {
            try { source.dispose(); } catch (RuntimeException | Error ignored) { /* Native cleanup is best effort. */ }
        }
        videoPublished = false;
        localFrameSeen = false;
    }

    /** Finds the remote video receiver and attaches one frame sink; safe to call repeatedly. */
    private synchronized void attachRemoteVideo() {
        if (closed.get() || videoReleased || remoteSink != null) return;
        RTCPeerConnection connection = peer;
        if (connection == null) return;
        RTCRtpTransceiver[] transceivers;
        try {
            transceivers = connection.getTransceivers();
        } catch (RuntimeException gone) {
            return;
        }
        if (transceivers == null) return;
        for (RTCRtpTransceiver transceiver : transceivers) {
            if (transceiver == null) continue;
            RTCRtpReceiver receiver = null;
            try {
                receiver = transceiver.getReceiver();
                if (receiver != null) {
                    MediaStreamTrack track = receiver.getTrack();
                    if (track instanceof VideoTrack video
                            && MediaStreamTrack.VIDEO_TRACK_KIND.equals(track.getKind())) {
                        attachRemoteTrack(video);
                    }
                }
            } catch (RuntimeException ignored) {
                /* A transceiver that is already stopping carries no usable track. */
            } finally {
                if (receiver != null) {
                    try { receiver.dispose(); } catch (RuntimeException | Error ignored) { /* Query result. */ }
                }
                try { transceiver.dispose(); } catch (RuntimeException | Error ignored) { /* Query result. */ }
            }
        }
    }

    private synchronized void attachRemoteTrack(VideoTrack track) {
        if (track == null || closed.get() || videoReleased || remoteSink != null) return;
        RemoteVideoSink sink = new RemoteVideoSink();
        try {
            track.addSink(sink);
        } catch (RuntimeException | LinkageError gone) {
            return;
        }
        remoteVideoTrack = track;
        remoteSink = sink;
    }

    /** A sink always releases the callback's frame reference; asynchronous work retains its own. */
    private void deliverFrame(VideoFrame frame, boolean local) {
        if (frame == null) return;
        if (closed.get() || videoReleased) {
            releaseFrame(frame);
            return;
        }
        long now = System.nanoTime();
        if (local) {
            localFrameSeen = true;
            if (now - localFrameAt < PREVIEW_FRAME_INTERVAL_NANOS) {
                releaseFrame(frame);
                return;
            }
            localFrameAt = now;
        } else {
            if (now - remoteFrameAt < REMOTE_FRAME_INTERVAL_NANOS) {
                releaseFrame(frame);
                return;
            }
            remoteFrameAt = now;
        }
        AtomicBoolean busy = local ? localFrameBusy : remoteFrameBusy;
        if (!busy.compareAndSet(false, true)) {
            releaseFrame(frame);
            return;
        }
        boolean retained = false;
        try {
            frame.retain();
            retained = true;
            videoWorker.execute(() -> {
                try {
                    BufferedImage image = toImage(frame);
                    if (image != null && !closed.get() && !videoReleased) {
                        try {
                            listener.videoFrame(this, image, local);
                        } catch (RuntimeException ignored) {
                            /* A failing UI callback must not end the call. */
                        }
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    /* A malformed frame is dropped; frames are never logged. */
                } finally {
                    busy.set(false);
                    releaseFrame(frame);
                }
            });
        } catch (RejectedExecutionException stopped) {
            busy.set(false);
            if (retained) releaseFrame(frame);
        } catch (RuntimeException | LinkageError gone) {
            busy.set(false);
            if (retained) releaseFrame(frame);
        } finally {
            releaseFrame(frame);
        }
    }

    /** Converts one I420 frame into a tightly packed ABGR image and bounds its size for the UI. */
    private static BufferedImage toImage(VideoFrame frame) {
        VideoFrameBuffer buffer = frame.buffer;
        if (buffer == null) return null;
        int width = buffer.getWidth();
        int height = buffer.getHeight();
        if (width <= 0 || height <= 0) return null;
        VideoFrameBuffer scaled = null;
        I420Buffer conversion = null;
        try {
            VideoFrameBuffer source = buffer;
            if (width > MAX_FRAME_WIDTH || height > MAX_FRAME_HEIGHT
                    || (long) width * height > (long) MAX_FRAME_WIDTH * MAX_FRAME_HEIGHT) {
                double scale = Math.min((double) MAX_FRAME_WIDTH / width, (double) MAX_FRAME_HEIGHT / height);
                int targetWidth = Math.max(2, (int) Math.floor(width * scale)) & ~1;
                int targetHeight = Math.max(2, (int) Math.floor(height * scale)) & ~1;
                scaled = buffer.cropAndScale(0, 0, width, height, targetWidth, targetHeight);
                if (scaled == null) return null;
                source = scaled;
                width = scaled.getWidth();
                height = scaled.getHeight();
            }
            /*
             * The converter converts non-I420 input itself but does not release that temporary
             * buffer. Hand it an I420 view we own so a hardware NV12 decoder cannot leak a buffer
             * on every frame.
             */
            if (!(source instanceof I420Buffer)) {
                conversion = source.toI420();
                if (conversion == null) return null;
                source = conversion;
            }
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
            byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            VideoBufferConverter.convertFromI420(source, pixels, FourCC.RGBA);
            return image;
        } catch (Exception | LinkageError failure) {
            return null;
        } finally {
            if (conversion != null) {
                try { conversion.release(); } catch (RuntimeException | Error ignored) { /* Best effort. */ }
            }
            if (scaled != null) {
                try { scaled.release(); } catch (RuntimeException | Error ignored) { /* Best effort. */ }
            }
        }
    }

    private static void releaseFrame(VideoFrame frame) {
        try { frame.release(); } catch (RuntimeException | Error ignored) { /* Best effort. */ }
    }

    /** Detects a camera that opened but never produced pixels and keeps the call alive. */
    private void scheduleVideoWatchdog() {
        try {
            worker.schedule(() -> submit(() -> {
                if (!closed.get() && !videoReleased && videoPublished && !localFrameSeen) {
                    notifyVideo("Камера не передаёт видео — звонок продолжается");
                }
            }), VIDEO_WATCHDOG_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            /* The call is already closing. */
        }
    }

    private void notifyVideo(String message) {
        try {
            listener.videoNotice(this, message);
        } catch (RuntimeException ignored) {
            /* A failing listener must not interrupt signaling. */
        }
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

    /** Disposes camera, sinks, peer connection, tracks, sources and factory exactly once. */
    private synchronized void release() {
        if (released) return;
        released = true;
        videoReleased = true;
        pendingOffer = null;
        releaseVideoInput();
        VideoTrack remote = remoteVideoTrack;
        VideoTrackSink sink = remoteSink;
        remoteVideoTrack = null;
        remoteSink = null;
        if (remote != null && sink != null) {
            try { remote.removeSink(sink); } catch (RuntimeException | Error ignored) { /* Native cleanup is best effort. */ }
        }
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
        videoWorker.shutdown();
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

    private final class LocalVideoSink implements VideoTrackSink {
        public void onVideoFrame(VideoFrame frame) { deliverFrame(frame, true); }
    }

    private final class RemoteVideoSink implements VideoTrackSink {
        public void onVideoFrame(VideoFrame frame) { deliverFrame(frame, false); }
    }

    private final class PeerObserver implements PeerConnectionObserver {
        public void onIceCandidate(RTCIceCandidate candidate) {
            /* Candidates are delivered inside the single gathered SDP frame. */
        }

        public void onTrack(RTCRtpTransceiver transceiver) {
            if (closed.get() || transceiver == null) return;
            RTCRtpReceiver receiver = null;
            try {
                receiver = transceiver.getReceiver();
                if (receiver != null) {
                    MediaStreamTrack track = receiver.getTrack();
                    if (track instanceof VideoTrack video
                            && MediaStreamTrack.VIDEO_TRACK_KIND.equals(track.getKind())) {
                        attachRemoteTrack(video);
                    }
                }
            } catch (RuntimeException ignored) {
                /* A stopping transceiver may report no receiver. */
            } finally {
                if (receiver != null) {
                    try { receiver.dispose(); } catch (RuntimeException | Error ignored) { /* Query result. */ }
                }
                try { transceiver.dispose(); } catch (RuntimeException | Error ignored) { /* Query result. */ }
            }
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
