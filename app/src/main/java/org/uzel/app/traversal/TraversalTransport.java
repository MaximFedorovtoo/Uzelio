package org.uzel.app.traversal;

import android.content.Context;
import android.net.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.webrtc.*;
import org.uzel.core.NetworkAccess;
import org.uzel.core.StunPreflight;

/** Reliable ICE/DTLS carrier. Callers MUST authenticate SDP and retain pinned mTLS inside streams. */
public final class TraversalTransport implements AutoCloseable {
    public static final String CONNECTED="CONNECTED",DISCONNECTED="DISCONNECTED",CONNECTING="CONNECTING",CLOSED="CLOSED";
    public interface Listener {
        void description(String type,String sdp);
        void state(String state);
        void failed(String reason);
    }
    public static final int MAX_STREAMS=24,MAX_MESSAGE=16*1024;
    private static final int WINDOW=96*1024,MAX_SDP=128*1024;
    private static final Object FACTORY_LOCK=new Object();
    private static boolean initialized;
    private static Network activeNetwork;
    private static int owners;
    private final Listener listener;
    private final int nodePort,chatPort;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final CompletableFuture<Void> cleanup=new CompletableFuture<>();
    private final AtomicReference<Thread> controlThread=new AtomicReference<>();
    private final ExecutorService control=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"traversal-control");t.setDaemon(true);controlThread.set(t);return t;});
    private final ExecutorService io=new ThreadPoolExecutor(0,72,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"traversal-stream");t.setDaemon(true);return t;});
    private final Map<String,Bridge> bridges=new ConcurrentHashMap<>();
    private final List<DataChannel> bootstrap=new ArrayList<>();
    private final PeerConnectionFactory factory;
    private final PeerConnection peer;
    private volatile boolean connected,armed;
    private boolean everConnected;
    private final ScheduledExecutorService initialTimer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"traversal-manual-deadline");t.setDaemon(true);return t;});
    private ScheduledFuture<?> initialDeadline;
    private ScheduledFuture<?> initialRetry;
    private List<IceCandidate> signedRemoteCandidates=Collections.emptyList();
    private boolean remoteDescriptionReady,initialIceFailed;
    private int initialRetryCount;
    private long initialExpiresAt;
    private boolean descriptionSent,incomingBootstrap;

    public TraversalTransport(Context context,Network physical,int localNodePort,Listener listener)throws IOException {
        this(context,physical,localNodePort,0,listener);
    }
    /** chatPort is the loopback TLS chat listener; 0 keeps chat labels disabled. */
    public TraversalTransport(Context context,Network physical,int localNodePort,int chatPort,Listener listener)throws IOException {
        if(context==null||physical==null||listener==null||localNodePort<1||localNodePort>65535||chatPort<0||chatPort>65535)throw new IllegalArgumentException("Traversal arguments");
        this.listener=listener;nodePort=localNodePort;this.chatPort=chatPort;
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps=manager.getNetworkCapabilities(physical);
        if(caps==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))throw new IOException("Physical network required");
        PeerConnectionFactory created=null;PeerConnection connection=null;
        synchronized(FACTORY_LOCK){
            if(owners>0&&!physical.equals(activeNetwork))throw new IOException("Concurrent traversal requires the same physical network");
            try{
                List<String> stunUrls=StunPreflight.select(new NetworkAccess(){
                    @Override public InetAddress[] resolve(String host)throws IOException{return physical.getAllByName(host);}
                    @Override public DatagramSocket datagram()throws IOException{
                        DatagramSocket socket=new DatagramSocket(null);
                        try{physical.bindSocket(socket);socket.bind(new InetSocketAddress(0));return socket;}
                        catch(IOException failure){socket.close();throw failure;}
                    }
                });
                Context app=context.getApplicationContext();
                if(!initialized){PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).setEnableInternalTracer(false).createInitializationOptions());initialized=true;}
                if(owners==0){
                    NetworkMonitor.getInstance().setNetworkChangeDetectorFactory((observer,ignored)->new PhysicalNetworkDetector(observer,app,physical));
                    activeNetwork=physical;
                }
                PeerConnectionFactory.Options options=new PeerConnectionFactory.Options();
                options.networkIgnoreMask=PeerConnectionFactory.Options.ADAPTER_TYPE_VPN|PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK;
                created=PeerConnectionFactory.builder().setOptions(options).createPeerConnectionFactory();
                List<PeerConnection.IceServer> iceServers=new ArrayList<>();
                for(String url:stunUrls)iceServers.add(PeerConnection.IceServer.builder(url).createIceServer());
                PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(iceServers);
                config.tcpCandidatePolicy=PeerConnection.TcpCandidatePolicy.DISABLED;
                config.enableIceGatheringOnAnyAddressPorts=false;
                config.continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
                connection=created.createPeerConnection(config,new PeerObserver());
                if(connection==null)throw new IOException("Peer connection creation failed");
                owners++;
            }catch(Throwable failure){
                if(connection!=null)connection.dispose();if(created!=null)created.dispose();
                control.shutdownNow();io.shutdownNow();initialTimer.shutdownNow();
                if(failure instanceof Error)throw (Error)failure;
                throw new IOException("Traversal initialization failed",failure);
            }
        }
        factory=created;peer=connection;
        // No listeners are armed during construction. SDP operations arm callbacks.
    }
    public boolean isConnected(){return connected&&!closed.get();}
    public void createOffer(){submit(()->{armInitialExchange();state(CONNECTING);ensureBootstrap();peer.createOffer(new SdpAdapter(){@Override public void onCreateSuccess(SessionDescription s){submit(()->setLocal(s));}},new MediaConstraints());});}
    public void acceptOffer(String sdp){validateSdp(sdp);submit(()->{armInitialExchange();rememberSignedCandidates(sdp);state(CONNECTING);peer.setRemoteDescription(new SdpAdapter(){@Override public void onSetSuccess(){submit(()->{remoteDescriptionReady=true;peer.createAnswer(new SdpAdapter(){@Override public void onCreateSuccess(SessionDescription s){submit(()->setLocal(s));}},new MediaConstraints());});}},new SessionDescription(SessionDescription.Type.OFFER,sdp));});}
    public void acceptAnswer(String sdp){validateSdp(sdp);submit(()->{armInitialExchange();rememberSignedCandidates(sdp);peer.setRemoteDescription(new SdpAdapter(){@Override public void onSetSuccess(){submit(()->{remoteDescriptionReady=true;if(initialIceFailed)scheduleInitialRetry();});}},new SessionDescription(SessionDescription.Type.ANSWER,sdp));});}
    private void armInitialExchange(){
        if(armed)return;armed=true;initialExpiresAt=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
        // Manual, signed SDP exchange may take minutes. Native ICE initial-pair
        // failure is not evidence that a previously working connection was lost.
        initialDeadline=initialTimer.schedule(()->submit(()->{
            if(!everConnected)fail("Initial manual pairing exceeded five minutes");
        }),5,TimeUnit.MINUTES);
    }
    // The caller verified this complete SDP before importing it. Retry only this
    // exact bounded list, with the same ufrag/password/fingerprint and local ports.
    private void rememberSignedCandidates(String sdp){
        signedRemoteCandidates=parseRemoteCandidates(sdp);remoteDescriptionReady=false;
    }
    private static List<IceCandidate> parseRemoteCandidates(String sdp){
        List<IceCandidate> result=new ArrayList<>();List<String> section=new ArrayList<>();
        int index=-1;String mid=null;
        for(String line:sdp.split("\\r?\\n")){
            if(line.startsWith("m=")){
                appendCandidates(result,section,mid,index);section.clear();mid=null;index++;
            }else if(line.startsWith("a=mid:")){mid=line.substring(6);}
            else if(line.startsWith("a=candidate:")){
                if(index<0||line.length()>4096)throw new IllegalArgumentException("Invalid remote candidate section");
                section.add(line.substring(2));
                if(section.size()+result.size()>64)throw new IllegalArgumentException("Too many remote candidates");
            }
        }
        appendCandidates(result,section,mid,index);return Collections.unmodifiableList(result);
    }
    private static void appendCandidates(List<IceCandidate> result,List<String> section,String mid,int index){
        if(section.isEmpty())return;
        if(mid==null||mid.isEmpty()||mid.length()>256)throw new IllegalArgumentException("Missing remote candidate mid");
        for(String value:section)result.add(new IceCandidate(mid,index,value));
    }
    private void scheduleInitialRetry(){
        if(closed.get()||everConnected||!initialIceFailed||!remoteDescriptionReady||signedRemoteCandidates.isEmpty()
                ||System.nanoTime()>=initialExpiresAt||initialRetryCount>=20||initialTimer.isShutdown())return;
        if(initialRetry!=null&&!initialRetry.isDone())return;
        // M144 HandleAllTimedOut destroys candidate-pair connections before FAILED.
        // Its Port stays alive until idle/pruned timeout; re-add promptly, rather
        // than remove/re-add live pairs (which would merely reuse old connections).
        initialRetry=initialTimer.schedule(()->submit(()->{initialRetry=null;retryFailedInitialCandidates();}),4,TimeUnit.SECONDS);
    }
    private void retryFailedInitialCandidates(){
        if(closed.get()||everConnected||System.nanoTime()>=initialExpiresAt||!remoteDescriptionReady)return;
        if(peer.iceGatheringState()!=PeerConnection.IceGatheringState.COMPLETE)return; // Gathering callback will schedule again.
        if(peer.iceConnectionState()!=PeerConnection.IceConnectionState.FAILED)return;
        IceCandidate[] candidates=signedRemoteCandidates.toArray(new IceCandidate[0]);
        if(candidates.length==0||initialRetryCount>=20)return;
        initialRetryCount++;
        boolean removed=peer.removeIceCandidates(candidates);
        int accepted=0;for(IceCandidate candidate:candidates)if(peer.addIceCandidate(candidate))accepted++;
        android.util.Log.i("UzelioTraversal","Initial ICE retry "+initialRetryCount+", removed="+removed+", accepted signed candidates "+accepted+"/"+candidates.length);
        if(peer.iceConnectionState()==PeerConnection.IceConnectionState.FAILED)scheduleInitialRetry();
        // Credentials, SDP and candidates are unchanged. This is not restartIce().
    }
    private static void validateSdp(String sdp){if(sdp==null||sdp.length()>MAX_SDP||!sdp.startsWith("v=0"))throw new IllegalArgumentException("Invalid or oversized SDP");}
    private void setLocal(SessionDescription s){descriptionSent=false;peer.setLocalDescription(new SdpAdapter(){@Override public void onSetSuccess(){submit(()->emitDescription());}},s);}
    private void emitDescription(){
        if(closed.get()||descriptionSent||peer.iceGatheringState()!=PeerConnection.IceGatheringState.COMPLETE)return;
        SessionDescription local=peer.getLocalDescription();if(local==null)return;
        if(local.description.length()>MAX_SDP){fail("Local SDP exceeds limit");return;}
        descriptionSent=true;listener.description(local.type.canonicalForm(),local.description);
    }
    private void ensureBootstrap(){if(!bootstrap.isEmpty())return;DataChannel channel=peer.createDataChannel("uzel.control.v1",reliable());if(channel==null)throw new IllegalStateException("Bootstrap channel failed");observeBootstrap(channel);}
    private DataChannel.Init reliable(){DataChannel.Init init=new DataChannel.Init();init.ordered=true;init.maxRetransmits=-1;init.maxRetransmitTimeMs=-1;init.protocol="uzel.byte.v1";return init;}
    private void observeBootstrap(DataChannel channel){bootstrap.add(channel);channel.registerObserver(new DataChannel.Observer(){public void onBufferedAmountChange(long old){}public void onStateChange(){}public void onMessage(DataChannel.Buffer data){submit(()->fail("Unexpected bootstrap payload"));}});}

    /** Returns a genuine connected TCP socket: suitable for Android Conscrypt createSocket. */
    public Socket openStream()throws IOException {
        return openLabeled("uzel.stream.v1.");
    }
    /** Chat traffic rides the same carrier but terminates at its own loopback TLS listener. */
    public Socket openChatStream()throws IOException {
        if(chatPort<1)throw new IOException("Chat traversal is not configured");
        return openLabeled("uzel.chat.v1.");
    }
    private Socket openLabeled(String prefix)throws IOException {
        return call(()->{
            if(!isConnected())throw new IOException("Traversal not connected");
            if(bridges.size()>=MAX_STREAMS)throw new IOException("Too many traversal streams");
            Socket caller=null,accepted=null;DataChannel channel=null;
            try(ServerSocket local=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))){
                local.setSoTimeout(2000);caller=new Socket();caller.connect(new InetSocketAddress("127.0.0.1",local.getLocalPort()),2000);
                for(int i=0;i<4;i++){Socket candidate=local.accept();if(candidate.getPort()==caller.getLocalPort()){accepted=candidate;break;}candidate.close();}
                if(accepted==null)throw new IOException("Loopback socket mismatch");
                String label=prefix+UUID.randomUUID();channel=peer.createDataChannel(label,reliable());
                if(channel==null)throw new IOException("Data channel creation failed");
                Bridge bridge=new Bridge(label,channel,accepted);bridges.put(label,bridge);bridge.start();return caller;
            }catch(Exception failure){closeSocket(caller);closeSocket(accepted);if(channel!=null){channel.close();channel.dispose();}throw failure;}
        });
    }
    private void incoming(DataChannel channel){
        String name=channel.label();int selected;
        if("uzel.control.v1".equals(name)&&!incomingBootstrap){incomingBootstrap=true;observeBootstrap(channel);return;}
        if(name.matches("uzel\\.stream\\.v1\\.[0-9a-f-]{36}"))selected=nodePort;
        else if(name.matches("uzel\\.chat\\.v1\\.[0-9a-f-]{36}")&&chatPort>0)selected=chatPort;
        else {channel.close();channel.dispose();return;}
        if(bridges.size()>=MAX_STREAMS||bridges.containsKey(name)){channel.close();channel.dispose();return;}
        Bridge bridge=new Bridge(name,channel,null);bridges.put(name,bridge);final int targetPort=selected;
        try{io.execute(()->{Socket target=new Socket();try{
            target.connect(new InetSocketAddress("127.0.0.1",targetPort),3000);
            synchronized(bridge.gate){if(bridge.stopped.get()){target.close();return;}bridge.socket=target;}bridge.start();
        }catch(Exception failure){closeSocket(target);bridge.close();}});}catch(RejectedExecutionException full){bridge.close();}
    }
    private void state(String value){if(armed)listener.state(value);}
    private void fail(String reason){if(closed.get())return;connected=false;try{if(armed)listener.failed(reason);}finally{close();}}
    private void submit(Runnable action){if(closed.get())return;try{control.execute(()->{if(!closed.get())try{action.run();}catch(Exception failure){fail("Traversal operation failed");}});}catch(RejectedExecutionException ignored){}}
    private <T>T call(Callable<T> action)throws IOException {
        if(closed.get())throw new IOException("Traversal closed");
        try{if(Thread.currentThread()==controlThread.get())return action.call();return control.submit(action).get(10,TimeUnit.SECONDS);}
        catch(Exception failure){throw new IOException("Traversal operation failed",failure);}
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true)){awaitCleanup();return;}connected=false;
        for(Bridge bridge:bridges.values())bridge.closeSocketOnly();io.shutdownNow();initialTimer.shutdownNow();
        try{control.execute(()->{
            try {
                for(Bridge bridge:new ArrayList<>(bridges.values()))bridge.disposeChannel();bridges.clear();
                for(DataChannel channel:bootstrap){channel.unregisterObserver();channel.close();channel.dispose();}bootstrap.clear();
                peer.close();peer.dispose();
                synchronized(FACTORY_LOCK){try{factory.dispose();}finally{if(--owners==0)activeNetwork=null;}}
                try{state(CLOSED);}catch(RuntimeException ignored){}
            } finally {cleanup.complete(null);control.shutdown();}
        });}catch(RejectedExecutionException ignored){cleanup.complete(null);}
        awaitCleanup();
    }
    private void awaitCleanup(){if(Thread.currentThread()!=controlThread.get())try{cleanup.get(5,TimeUnit.SECONDS);}catch(Exception ignored){}}
    private static void closeSocket(Socket socket){if(socket!=null)try{socket.close();}catch(IOException ignored){}}
    private class SdpAdapter implements SdpObserver {
        public void onCreateSuccess(SessionDescription s){}public void onSetSuccess(){}
        public void onCreateFailure(String error){submit(()->fail("SDP creation failed"));}
        public void onSetFailure(String error){submit(()->fail("SDP rejected"));}
    }
    private final class PeerObserver implements PeerConnection.Observer {
        public void onSignalingChange(PeerConnection.SignalingState state){}
        public void onIceConnectionChange(PeerConnection.IceConnectionState state){if(armed&&state==PeerConnection.IceConnectionState.FAILED)submit(()->{if(!everConnected){initialIceFailed=true;scheduleInitialRetry();}});}
        public void onIceConnectionReceivingChange(boolean receiving){}
        public void onIceGatheringChange(PeerConnection.IceGatheringState state){if(armed&&state==PeerConnection.IceGatheringState.COMPLETE)submit(()->{emitDescription();if(initialIceFailed)scheduleInitialRetry();});}
        public void onIceCandidate(IceCandidate candidate){}public void onIceCandidatesRemoved(IceCandidate[] candidates){}
        public void onAddStream(MediaStream stream){}public void onRemoveStream(MediaStream stream){}public void onRenegotiationNeeded(){}
        public void onDataChannel(DataChannel channel){submit(()->incoming(channel));}
        public void onConnectionChange(PeerConnection.PeerConnectionState value){if(!armed)return;submit(()->{
            if(value==PeerConnection.PeerConnectionState.CONNECTED){if(!connected){connected=true;everConnected=true;if(initialDeadline!=null)initialDeadline.cancel(false);if(initialRetry!=null)initialRetry.cancel(false);initialTimer.shutdownNow();state(CONNECTED);}}
            else if(value==PeerConnection.PeerConnectionState.DISCONNECTED||value==PeerConnection.PeerConnectionState.FAILED){
                connected=false;
                if(everConnected){state(DISCONNECTED);fail("Established ICE connection lost; create a new transport");}
                else{android.util.Log.i("UzelioTraversal","Initial phase pc="+value+", ice="+peer.iceConnectionState()+", gathering="+peer.iceGatheringState());
                    if(peer.iceConnectionState()==PeerConnection.IceConnectionState.FAILED){initialIceFailed=true;scheduleInitialRetry();}}
                // Keep the gathered local ports alive while the signed answer is
                // manually transferred; a late inbound check can create prflx pairs.
            }
        });}
    }

    private final class Bridge implements DataChannel.Observer {
        final String name;final DataChannel channel;volatile Socket socket;
        final AtomicBoolean stopped=new AtomicBoolean(),disposed=new AtomicBoolean();final Object gate=new Object();
        final byte[] inbound=new byte[WINDOW];
        int credit=WINDOW,queued,readPosition,writePosition;boolean remoteFin,remoteFinProcessed,localFin;
        Bridge(String name,DataChannel channel,Socket socket){this.name=name;this.channel=channel;this.socket=socket;channel.registerObserver(this);}
        void start()throws IOException {socket.setTcpNoDelay(true);socket.setSendBufferSize(32*1024);socket.setReceiveBufferSize(32*1024);
            try{io.execute(this::readLocal);io.execute(this::writeLocal);}catch(RejectedExecutionException full){close();}}
        public void onBufferedAmountChange(long old){synchronized(gate){gate.notifyAll();}}
        public void onStateChange(){synchronized(gate){gate.notifyAll();}submit(()->{if(!disposed.get()&&channel.state()==DataChannel.State.CLOSED)close();});}
        public void onMessage(DataChannel.Buffer incoming){
            try{
                ByteBuffer data=incoming.data.duplicate();int size=data.remaining();
                if(!incoming.binary||size<1||size>MAX_MESSAGE)throw new IOException("Frame bounds");
                int type=data.get()&255;
                synchronized(gate){
                    if(stopped.get())return;
                    if(type==1){
                        int bytes=data.remaining();if(remoteFin||bytes<1||queued+bytes>WINDOW)throw new IOException("Receive window");
                        int first=Math.min(bytes,WINDOW-writePosition);data.get(inbound,writePosition,first);
                        if(first<bytes)data.get(inbound,0,bytes-first);
                        writePosition=(writePosition+bytes)%WINDOW;queued+=bytes;gate.notifyAll();
                    }
                    else if(type==2&&size==5){int amount=data.getInt();if(amount<=0||credit+amount>WINDOW)throw new IOException("Credit bounds");credit+=amount;gate.notifyAll();}
                    else if(type==3&&size==1&&!remoteFin){remoteFin=true;gate.notifyAll();}
                    else throw new IOException("Invalid frame");
                }
            }catch(Exception malformed){close();}
        }
        void readLocal(){try{
            byte[] buffer=new byte[MAX_MESSAGE-1];InputStream input=socket.getInputStream();
            while(!stopped.get()){
                int maximum;long deadline=System.nanoTime()+30_000_000_000L;
                synchronized(gate){while(credit<=0&&!stopped.get()){if(System.nanoTime()>deadline)throw new IOException("Credit timeout");gate.wait(100);}if(stopped.get())return;maximum=Math.min(buffer.length,credit);}
                int count=input.read(buffer,0,maximum);
                if(count<0){sendFrame(new byte[]{3});synchronized(gate){localFin=true;}finishIfDone();return;}
                synchronized(gate){credit-=count;}
                byte[] frame=new byte[count+1];frame[0]=1;System.arraycopy(buffer,0,frame,1,count);sendFrame(frame);
            }
        }catch(Exception failure){close();}}
        void writeLocal(){try{
            OutputStream output=socket.getOutputStream();byte[] bytes=new byte[MAX_MESSAGE-1];
            while(!stopped.get()){
                int count;
                synchronized(gate){
                    while(queued==0&&!remoteFin&&!stopped.get())gate.wait();
                    if(stopped.get())return;
                    count=Math.min(queued,bytes.length);
                    int first=Math.min(count,WINDOW-readPosition);System.arraycopy(inbound,readPosition,bytes,0,first);
                    if(first<count)System.arraycopy(inbound,0,bytes,first,count-first);
                    readPosition=(readPosition+count)%WINDOW;
                }
                if(count==0){socket.shutdownOutput();synchronized(gate){remoteFinProcessed=true;}finishIfDone();return;}
                output.write(bytes,0,count);
                synchronized(gate){queued-=count;}
                sendFrame(ByteBuffer.allocate(5).put((byte)2).putInt(count).array());
            }
        }catch(Exception failure){close();}}
        void sendFrame(byte[] bytes)throws Exception {
            long deadline=System.nanoTime()+30_000_000_000L;
            while(!stopped.get()){
                boolean sent=call(()->!stopped.get()&&channel.state()==DataChannel.State.OPEN
                        &&channel.bufferedAmount()+bytes.length<=WINDOW&&channel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes),true)));
                if(sent)return;if(System.nanoTime()>deadline)throw new IOException("Data channel backpressure timeout");
                synchronized(gate){gate.wait(25);}
            }
            throw new IOException("Stream closed");
        }
        void finishIfDone(){synchronized(gate){if(!localFin||!remoteFinProcessed)return;}
            try{io.execute(()->{try{long end=System.nanoTime()+2_000_000_000L;
                while(!stopped.get()&&System.nanoTime()<end&&call(()->disposed.get()?0L:channel.bufferedAmount())>0)Thread.sleep(10);
            }catch(Exception ignored){}finally{close();}});}catch(RejectedExecutionException full){close();}}
        void closeSocketOnly(){synchronized(gate){stopped.set(true);closeSocket(socket);gate.notifyAll();}}
        void close(){if(!stopped.compareAndSet(false,true))return;synchronized(gate){closeSocket(socket);gate.notifyAll();}
            try{control.execute(()->{bridges.remove(name,this);disposeChannel();});}catch(RejectedExecutionException ignored){}}
        void disposeChannel(){if(disposed.compareAndSet(false,true)){channel.unregisterObserver();channel.close();channel.dispose();}}
    }
}
