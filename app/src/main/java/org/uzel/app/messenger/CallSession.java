package org.uzel.app.messenger;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import org.uzel.core.NetworkAccess;
import org.uzel.core.StunPreflight;
import org.uzel.messenger.chat.ChatService;
import org.uzel.messenger.chat.ChatWire;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One point-to-point audio call carried by WebRTC. SDP travels only inside the
 * authenticated chat frames from {@link ChatService#sendCall}; it is never logged
 * or persisted. A fresh instance is required for every call.
 */
public final class CallSession implements AutoCloseable {
    public enum State {IDLE,CALLING,RINGING,CONNECTED,ENDED,FAILED}
    public interface Listener {void onCallState(CallSession session,State state);}

    private static final long RING_TIMEOUT_MS=60_000,CONNECT_TIMEOUT_MS=30_000,GATHER_TIMEOUT_MS=12_000;
    private static final int MAX_SDP=65_536;
    private static final Object INIT_LOCK=new Object();
    private static boolean initialized;
    private static final AudioManager.OnAudioFocusChangeListener FOCUS_CHANGE=change->{};

    private final Context app;
    private final ChatService service;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object lock=new Object();
    private final AtomicBoolean closed=new AtomicBoolean(),disposed=new AtomicBoolean();
    private final CompletableFuture<Void> cleanup=new CompletableFuture<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"uzelio-call");thread.setDaemon(true);workerThread=thread;return thread;
    });
    private volatile Thread workerThread;
    private volatile State state=State.IDLE;
    private String contactId="",callId="";
    private boolean offerer,accepted,remoteDescriptionSet,localDescriptionSent;
    private String pendingOffer;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private JavaAudioDeviceModule audioDeviceModule;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private RtpSender audioSender;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private int previousAudioMode=AudioManager.MODE_NORMAL;
    private final Runnable timeout=()->{
        State current=state();
        if(current==State.CALLING||current==State.RINGING)terminate(State.FAILED,true);
    };
    private final Runnable gatherTimeout=()->submit(this::emitLocalDescription);

    public CallSession(Context context,ChatService service,Listener listener){
        if(context==null||service==null)throw new IllegalArgumentException("Call dependencies");
        Context application=context.getApplicationContext();
        this.app=application==null?context:application;
        this.service=service;
        this.listener=listener;
    }

    public State state(){synchronized(lock){return state;}}
    public String contactId(){synchronized(lock){return contactId;}}
    public boolean isOfferer(){synchronized(lock){return offerer;}}
    public boolean isAccepted(){synchronized(lock){return accepted;}}
    public boolean isActive(){
        State current=state();
        return current==State.CALLING||current==State.RINGING||current==State.CONNECTED;
    }

    /** Outgoing call: build the audio offer and wait for the contact's authenticated answer. */
    public void start(String contactId){
        if(closed.get())throw new IllegalStateException("Call is closed");
        if(contactId==null||contactId.isBlank())throw new IllegalArgumentException("Call contact");
        synchronized(lock){
            if(state!=State.IDLE)throw new IllegalStateException("Call already started");
            this.contactId=contactId;this.callId=UUID.randomUUID().toString();this.offerer=true;
        }
        transition(State.CALLING);scheduleTimeout();
        submit(this::prepareOutgoing);
    }

    /** Incoming offer from the chat channel. It stays RINGING until the user decides. */
    public void incoming(String contactId,String callId,String sdp){
        if(closed.get())throw new IllegalStateException("Call is closed");
        if(contactId==null||contactId.isBlank()||!validCallId(callId)||!validSdp(sdp))throw new IllegalArgumentException("Incoming call");
        synchronized(lock){
            if(state!=State.IDLE)throw new IllegalStateException("Call already started");
            this.contactId=contactId;this.callId=callId;this.offerer=false;this.accepted=false;this.pendingOffer=sdp;
        }
        transition(State.RINGING);scheduleTimeout();
        // Native setup starts only on accept: ringing must not allocate a microphone session.
    }

    /** Explicit user decision: answer the stored incoming offer. */
    public void accept(){
        if(closed.get())return;
        submit(()->{
            synchronized(lock){
                if(offerer||state!=State.RINGING||accepted)return;
                accepted=true;
            }
            acquireAudioFocus();
            scheduleTimeout(CONNECT_TIMEOUT_MS);
            notifyState(State.RINGING);
            if(peer==null)prepareIncoming();
            else if(remoteDescriptionSet)createAnswer();
        });
    }

    public void reject(){
        if(closed.get()||state()!=State.RINGING)return;
        terminate(State.ENDED,true);
    }

    public void hangup(){
        if(closed.get())return;
        State current=state();
        if(current==State.CALLING||current==State.RINGING||current==State.CONNECTED)terminate(State.ENDED,true);
    }

    /** Authenticated answer to our offer. */
    public void answer(String callId,String sdp){
        if(closed.get()||!validSdp(sdp))return;
        synchronized(lock){
            if(!offerer||!this.callId.equals(callId))return;
        }
        submit(()->{
            if(peer==null||state()!=State.CALLING)return;
            try{
                peer.setRemoteDescription(new SdpAdapter(){
                    @Override public void onSetSuccess(){submit(()->{
                        remoteDescriptionSet=true;
                        scheduleTimeout(CONNECT_TIMEOUT_MS);
                    });}
                    @Override public void onSetFailure(String error){submit(CallSession.this::fail);}
                },new SessionDescription(SessionDescription.Type.ANSWER,sdp));
            }catch(RuntimeException failure){fail();}
        });
    }

    /** Authenticated remote hangup. */
    public void end(String callId){
        if(closed.get())return;
        synchronized(lock){
            if(!this.callId.equals(callId))return;
        }
        terminate(State.ENDED,false);
    }

    @Override public void close(){
        if(!closed.compareAndSet(false,true)){awaitCleanup();return;}
        main.removeCallbacks(timeout);main.removeCallbacks(gatherTimeout);
        try{
            worker.execute(()->{
                try{
                    if(isActive())sendEnd();
                    transition(State.ENDED);
                }finally{
                    release();
                    cleanup.complete(null);
                    worker.shutdown();
                }
            });
        }catch(RejectedExecutionException ignored){
            release();cleanup.complete(null);
        }
        awaitCleanup();
    }

    private void prepareOutgoing(){
        try{
            createPeerConnection();
            attachAudio();
            acquireAudioFocus();
            peer.createOffer(new SdpAdapter(){
                @Override public void onCreateSuccess(SessionDescription description){submit(()->setLocalDescription(description));}
            },new MediaConstraints());
        }catch(RuntimeException|LinkageError failure){fail();}
    }

    private void prepareIncoming(){
        try{
            createPeerConnection();
            attachAudio();
            String offer;
            synchronized(lock){offer=pendingOffer;pendingOffer=null;}
            if(offer==null)throw new IllegalStateException("Incoming offer is missing");
            peer.setRemoteDescription(new SdpAdapter(){
                @Override public void onSetSuccess(){submit(()->{
                    remoteDescriptionSet=true;
                    if(accepted)createAnswer();
                });}
                @Override public void onSetFailure(String error){submit(CallSession.this::fail);}
            },new SessionDescription(SessionDescription.Type.OFFER,offer));
        }catch(RuntimeException|LinkageError failure){fail();}
    }

    private void createAnswer(){
        PeerConnection connection=peer;
        if(connection==null||closed.get())return;
        try{
            connection.createAnswer(new SdpAdapter(){
                @Override public void onCreateSuccess(SessionDescription description){submit(()->setLocalDescription(description));}
            },new MediaConstraints());
        }catch(RuntimeException failure){fail();}
    }

    private void setLocalDescription(SessionDescription description){
        PeerConnection connection=peer;
        if(connection==null||closed.get())return;
        try{
            connection.setLocalDescription(new SdpAdapter(){
                @Override public void onSetSuccess(){submit(()->{
                    PeerConnection current=peer;
                    if(current==null)return;
                    if(current.iceGatheringState()==PeerConnection.IceGatheringState.COMPLETE)emitLocalDescription();
                    else main.postDelayed(gatherTimeout,GATHER_TIMEOUT_MS);
                });}
                @Override public void onSetFailure(String error){submit(CallSession.this::fail);}
            },description);
        }catch(RuntimeException failure){fail();}
    }

    /** Trickle is deliberately disabled: the complete SDP is delivered as one chat frame. */
    private void emitLocalDescription(){
        PeerConnection connection=peer;
        if(closed.get()||localDescriptionSent||connection==null)return;
        SessionDescription local=connection.getLocalDescription();
        if(local==null||local.description==null)return;
        if(local.description.length()>MAX_SDP||!local.description.startsWith("v=0")){fail();return;}
        int kind=local.type==SessionDescription.Type.OFFER?ChatWire.CALL_OFFER
                :local.type==SessionDescription.Type.ANSWER?ChatWire.CALL_ANSWER:0;
        if(kind==0)return;
        localDescriptionSent=true;
        try{service.sendCall(contactId,kind,callId,local.description);}
        catch(Exception failure){fail();}
    }

    private void createPeerConnection(){
        initialize(app);
        JavaAudioDeviceModule module=JavaAudioDeviceModule.builder(app)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setEnableVolumeLogger(false)
                .createAudioDeviceModule();
        if(module==null)throw new IllegalStateException("Audio device module");
        PeerConnectionFactory created=null;
        try{
            PeerConnectionFactory.Options options=new PeerConnectionFactory.Options();
            options.networkIgnoreMask=PeerConnectionFactory.Options.ADAPTER_TYPE_VPN
                    |PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK;
            created=PeerConnectionFactory.builder().setOptions(options)
                    .setAudioDeviceModule(module).createPeerConnectionFactory();
            if(created==null)throw new IllegalStateException("Peer connection factory");
            PeerConnection.RTCConfiguration configuration=new PeerConnection.RTCConfiguration(iceServers());
            configuration.tcpCandidatePolicy=PeerConnection.TcpCandidatePolicy.DISABLED;
            configuration.enableIceGatheringOnAnyAddressPorts=false;
            configuration.continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
            PeerConnection connection=created.createPeerConnection(configuration,new PeerObserver());
            if(connection==null)throw new IllegalStateException("Peer connection");
            factory=created;peer=connection;audioDeviceModule=module;
        }catch(RuntimeException|LinkageError failure){
            if(created!=null)try{created.dispose();}catch(RuntimeException ignored){}
            try{module.release();}catch(RuntimeException ignored){}
            throw failure;
        }
    }

    private void attachAudio(){
        PeerConnectionFactory created=factory;
        PeerConnection connection=peer;
        if(created==null||connection==null)throw new IllegalStateException("Peer connection is not ready");
        AudioSource source=created.createAudioSource(new MediaConstraints());
        if(source==null)throw new IllegalStateException("Audio source");
        AudioTrack track=created.createAudioTrack("uzelio.audio",source);
        if(track==null){source.dispose();throw new IllegalStateException("Audio track");}
        audioSource=source;audioTrack=track;
        RtpSender sender=connection.addTrack(track,Collections.singletonList("uzelio.call"));
        if(sender==null){
            audioSource=null;audioTrack=null;track.dispose();source.dispose();
            throw new IllegalStateException("Audio sender");
        }
        audioSender=sender;
    }

    private List<PeerConnection.IceServer> iceServers(){
        List<PeerConnection.IceServer> servers=new ArrayList<>();
        try{
            for(String url:StunPreflight.select(physicalAccess(),StunPreflight.DEFAULT_SERVERS,1500))
                try{servers.add(PeerConnection.IceServer.builder(url).createIceServer());}
                catch(RuntimeException ignored){/* A malformed hint cannot disable host candidates. */}
        }catch(Exception unavailable){/* Host candidates still work on a shared network. */}
        return servers;
    }

    private NetworkAccess physicalAccess(){
        ConnectivityManager manager=(ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network physical=null;
        if(manager!=null){
            Network active=manager.getActiveNetwork();
            NetworkCapabilities capabilities=active==null?null:manager.getNetworkCapabilities(active);
            if(capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))physical=active;
            else for(Network candidate:manager.getAllNetworks()){
                capabilities=manager.getNetworkCapabilities(candidate);
                if(capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        &&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)){physical=candidate;break;}
            }
        }
        final Network selected=physical;
        return new NetworkAccess(){
            @Override public InetAddress[] resolve(String host)throws IOException{
                return selected==null?super.resolve(host):selected.getAllByName(host);
            }
            @Override public DatagramSocket datagram()throws IOException{
                if(selected==null)return super.datagram();
                DatagramSocket socket=new DatagramSocket(null);
                try{selected.bindSocket(socket);socket.bind(new InetSocketAddress(0));socket.setSoTimeout(30000);return socket;}
                catch(IOException failure){socket.close();throw failure;}
            }
        };
    }

    private void connected(){
        if(disposed.get())return;
        State current=state();
        if(current!=State.CALLING&&current!=State.RINGING)return;
        main.removeCallbacks(timeout);
        transition(State.CONNECTED);
    }

    private void fail(){
        if(closed.get())return;
        try{worker.execute(this::failNow);}catch(RejectedExecutionException ignored){}
    }

    private void failNow(){
        if(disposed.get())return;
        synchronized(lock){if(state==State.ENDED||state==State.FAILED)return;}
        if(isActive())sendEnd();
        transition(State.FAILED);
        release();
    }

    private void terminate(State outcome,boolean signal){
        if(closed.get())return;
        try{
            worker.execute(()->{
                if(disposed.get())return;
                synchronized(lock){if(state==State.ENDED||state==State.FAILED)return;}
                if(signal&&isActive())sendEnd();
                transition(outcome);
                release();
            });
        }catch(RejectedExecutionException ignored){}
    }

    private void sendEnd(){
        String id,contact;
        synchronized(lock){id=callId;contact=contactId;}
        if(id==null||id.isEmpty())return;
        try{service.sendCall(contact,ChatWire.CALL_END,id,"");}catch(Exception ignored){/* Signaling is best effort. */}
    }

    private void submit(Runnable action){
        if(closed.get())return;
        try{
            worker.execute(()->{
                try{action.run();}
                catch(RuntimeException|LinkageError failure){fail();}
            });
        }catch(RejectedExecutionException ignored){}
    }

    private void scheduleTimeout(){scheduleTimeout(RING_TIMEOUT_MS);}
    private void scheduleTimeout(long delay){
        main.removeCallbacks(timeout);
        main.postDelayed(timeout,delay);
    }

    private void transition(State next){
        synchronized(lock){
            if(state==next)return;
            if((state==State.ENDED||state==State.FAILED)&&next!=state)return;
            state=next;
        }
        notifyState(next);
    }

    private void notifyState(State value){
        Listener target=listener;
        if(target==null)return;
        main.post(()->{
            try{target.onCallState(this,value);}catch(RuntimeException ignored){/* UI only */}
        });
    }

    private void acquireAudioFocus(){
        AudioManager manager=(AudioManager)app.getSystemService(Context.AUDIO_SERVICE);
        if(manager==null)return;
        try{
            previousAudioMode=manager.getMode();
            AudioFocusRequest request=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(FOCUS_CHANGE)
                    .build();
            audioManager=manager;audioFocusRequest=request;
            manager.requestAudioFocus(request);
            manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        }catch(RuntimeException ignored){/* Audio mode is a hint; the call may still work. */}
    }

    private void releaseAudioFocus(){
        AudioManager manager=audioManager;
        AudioFocusRequest request=audioFocusRequest;
        int mode=previousAudioMode;
        audioManager=null;audioFocusRequest=null;
        if(manager==null)return;
        try{if(request!=null)manager.abandonAudioFocusRequest(request);}catch(RuntimeException ignored){}
        try{manager.setMode(mode);}catch(RuntimeException ignored){}
    }

    private void release(){
        if(!disposed.compareAndSet(false,true))return;
        main.removeCallbacks(timeout);main.removeCallbacks(gatherTimeout);
        releaseAudioFocus();
        PeerConnection connection=peer;peer=null;
        RtpSender sender=audioSender;audioSender=null;
        AudioTrack track=audioTrack;audioTrack=null;
        AudioSource source=audioSource;audioSource=null;
        PeerConnectionFactory created=factory;factory=null;
        JavaAudioDeviceModule module=audioDeviceModule;audioDeviceModule=null;
        if(connection!=null)try{connection.close();}catch(RuntimeException ignored){}
        if(sender!=null)try{sender.dispose();}catch(RuntimeException ignored){}
        if(track!=null)try{track.dispose();}catch(RuntimeException ignored){}
        if(source!=null)try{source.dispose();}catch(RuntimeException ignored){}
        if(connection!=null)try{connection.dispose();}catch(RuntimeException ignored){}
        if(created!=null)try{created.dispose();}catch(RuntimeException ignored){}
        if(module!=null)try{module.release();}catch(RuntimeException ignored){}
        synchronized(lock){pendingOffer=null;}
    }

    private void awaitCleanup(){
        if(Thread.currentThread()!=workerThread)try{cleanup.get(3,TimeUnit.SECONDS);}catch(Exception ignored){}
    }

    private static boolean validCallId(String id){return id!=null&&id.matches("[A-Za-z0-9-]{1,64}");}
    private static boolean validSdp(String sdp){return sdp!=null&&sdp.length()<=MAX_SDP&&sdp.startsWith("v=0");}

    /**
     * TraversalTransport and calls share one process-wide WebRTC initialization.
     * Probe the same condition WebRTC itself checks so the first user of either
     * feature initializes once and the other does not reset global state.
     */
    private static void initialize(Context app){
        synchronized(INIT_LOCK){
            if(initialized)return;
            if(webrtcInitialized()){initialized=true;return;}
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app)
                    .setEnableInternalTracer(false).createInitializationOptions());
            initialized=true;
        }
    }

    private static boolean webrtcInitialized(){
        try{
            Class<?> library=Class.forName("org.webrtc.NativeLibrary");
            Method loaded=library.getDeclaredMethod("isLoaded");
            loaded.setAccessible(true);
            Object value=loaded.invoke(null);
            if(!(value instanceof Boolean)||!(Boolean)value)return false;
            Class<?> contextUtils=Class.forName("org.webrtc.ContextUtils");
            Method context=contextUtils.getDeclaredMethod("getApplicationContext");
            context.setAccessible(true);
            return context.invoke(null)!=null;
        }catch(Throwable unavailable){return false;}
    }

    private class SdpAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description){}
        @Override public void onSetSuccess(){}
        @Override public void onCreateFailure(String error){submit(CallSession.this::fail);}
        @Override public void onSetFailure(String error){submit(CallSession.this::fail);}
    }

    private final class PeerObserver implements PeerConnection.Observer {
        @Override public void onSignalingChange(PeerConnection.SignalingState value){}
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState value){
            if(value==PeerConnection.IceConnectionState.FAILED)submit(CallSession.this::fail);
        }
        @Override public void onIceConnectionReceivingChange(boolean receiving){}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState value){
            if(value==PeerConnection.IceGatheringState.COMPLETE)submit(CallSession.this::emitLocalDescription);
        }
        @Override public void onIceCandidate(IceCandidate candidate){}
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates){}
        @Override public void onAddStream(MediaStream stream){}
        @Override public void onRemoveStream(MediaStream stream){}
        @Override public void onDataChannel(DataChannel channel){}
        @Override public void onRenegotiationNeeded(){}
        @Override public void onConnectionChange(PeerConnection.PeerConnectionState value){
            if(value==PeerConnection.PeerConnectionState.CONNECTED)submit(CallSession.this::connected);
            else if(value==PeerConnection.PeerConnectionState.FAILED)submit(CallSession.this::fail);
        }
    }
}
