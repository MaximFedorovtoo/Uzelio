package org.uzel.desktop;

import org.uzel.core.*;
import org.uzel.desktop.messenger.DesktopMessenger;
import org.uzel.desktop.traversal.DesktopTraversalTransport;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/** Target-pinned, recipient-encrypted SDP exchange. Credentials stay in memory; core mTLS still authenticates every stream. */
final class DesktopPairingManager implements AutoCloseable {
    // Native ICE gathering can exceed30 seconds; match Android's bounded two-minute backup window.
    private static final long BACKUP_TIMEOUT_MILLIS=120_000;
    interface Listener {void unavailable(String fingerprint);}
    interface TransportListener {void description(String type,String sdp);void state(String state);void failed(String reason);}
    interface Transport extends AutoCloseable {void createOffer();void acceptOffer(String sdp);void acceptAnswer(String sdp);boolean isConnected();Socket openStream()throws IOException;default Socket openChatStream()throws IOException{return null;}void close();}
    @FunctionalInterface interface TransportFactory {Transport create(int port,TransportListener listener)throws IOException;}
    private static Transport nativeTransport(int port,TransportListener listener)throws IOException {
        // Chat streams bridge to the desktop's fixed chat TLS listener, never to the VPN node port.
        DesktopTraversalTransport delegate=new DesktopTraversalTransport(port,DesktopMessenger.CHAT_PORT,new DesktopTraversalTransport.Listener(){
            public void description(String type,String sdp){listener.description(type,sdp);}public void state(String state){listener.state(state);}public void failed(String reason){listener.failed(reason);}
        });
        return new Transport(){public void createOffer(){delegate.createOffer();}public void acceptOffer(String sdp){delegate.acceptOffer(sdp);}public void acceptAnswer(String sdp){delegate.acceptAnswer(sdp);}public boolean isConnected(){return delegate.isConnected();}public Socket openStream()throws IOException{return delegate.openStream();}public Socket openChatStream()throws IOException{return delegate.openChatStream();}public void close(){delegate.close();}};
    }
    private final Node node;
    private final TlsIdentity identity;
    private final DiscoveryIdentity discoveryIdentity;
    private final DesktopDiscoveryClient discovery;
    private final Listener listener;
    private final TransportFactory factory;
    private final long timeoutMillis;
    private final ExecutorService serial=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"uzelio-automatic-pairing");thread.setDaemon(true);return thread;});
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"uzelio-pairing-deadline");thread.setDaemon(true);return thread;});
    private final Map<String,Pair> active=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,Invitation> invitations=new ConcurrentHashMap<>();
    private final Map<String,Long> handled=new LinkedHashMap<>();
    private final Map<String,DeferredSignal> deferred=new LinkedHashMap<>();
    private final Map<String,Long> backupRetryAfter=new HashMap<>();
    private final PairingCode.ReplayWindow replay=new PairingCode.ReplayWindow();
    private final Runnable signalCallback=this::signalsChanged;
    private volatile boolean closed;
    private volatile String stage="IDLE";
    private Pair pending;
    private long lastIncoming;
    private enum Acceptance {ACCEPTED,DEFERRED,REJECTED}
    private record DeferredSignal(PairingCode code,long signalExpiresAt){long expiresAt(){return Math.min(code.expiresAt,signalExpiresAt);}}
    private static final class Pair {
        CompletableFuture<Invitation> completion;
        final String token=TlsIdentity.randomSecret();
        final String target;
        final Set<String> signals=new HashSet<>();
        Transport transport;Node.TransportRegistration registration;PairingCode offer,remote;
        long deadline;boolean disposed,activated,consumed,background;
        Pair(String target,CompletableFuture<Invitation> completion,long timeoutMillis){this.target=target;this.completion=completion;deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);}
    }
    DesktopPairingManager(Node node,TlsIdentity identity,DiscoveryIdentity discoveryIdentity,DesktopDiscoveryClient discovery,Listener listener){this(node,identity,discoveryIdentity,discovery,listener,DesktopPairingManager::nativeTransport,180_000);}
    DesktopPairingManager(Node node,TlsIdentity identity,DiscoveryIdentity discoveryIdentity,DesktopDiscoveryClient discovery,Listener listener,TransportFactory factory,long timeoutMillis){
        this.node=Objects.requireNonNull(node);this.identity=Objects.requireNonNull(identity);this.discoveryIdentity=Objects.requireNonNull(discoveryIdentity);this.discovery=Objects.requireNonNull(discovery);this.listener=Objects.requireNonNull(listener);this.factory=Objects.requireNonNull(factory);
        if(timeoutMillis<1 || timeoutMillis>300_000)throw new IllegalArgumentException("Pairing deadline");this.timeoutMillis=timeoutMillis;
        discovery.setSignalListener(signalCallback);timer.scheduleWithFixedDelay(()->submit(()->{
            if(pending!=null && System.nanoTime()>=pending.deadline){Pair expired=pending;pending=null;setStage(expired,"EXPIRED");dispose(expired,"Автосопряжение превысило время ожидания");}
            if(!deferred.isEmpty())processSignals();
        }),100,100,TimeUnit.MILLISECONDS);signalsChanged();
    }
    Invitation invitation(String fingerprint){return invitations.get(fingerprint);}
    List<Invitation> invitations(){return List.copyOf(invitations.values());}
    int backupCount(){return invitations.size();}
    String state(){return stage;}
    CompletableFuture<Invitation> connect(DiscoveryPresence recipient){
        return connect(recipient,false);
    }
    CompletableFuture<Invitation> connectBackup(DiscoveryPresence recipient){return connect(recipient,true);}
    void maintainBackups(){submit(()->{
        if(closed || pending!=null || !node.isSharing() || node.measuredUpload()<=0 || active.size()>=2)return;
        long now=System.currentTimeMillis();backupRetryAfter.entrySet().removeIf(entry->entry.getValue()<=now);
        DiscoveryPresence next=BackupPeerSelector.next(identity.fingerprint,discovery.presences(),invitations.keySet(),now,backupRetryAfter.keySet());
        if(next!=null){backupRetryAfter.put(next.fingerprint,now+30_000);connectBackup(next);}
    });}
    private void setStage(Pair pair,String value){if(!pair.background)stage=value;}
    private CompletableFuture<Invitation> connect(DiscoveryPresence recipient,boolean background){
        CompletableFuture<Invitation> result=new CompletableFuture<>();
        submit(()->{
            if(result.isCancelled())return;
            if(recipient==null || recipient.expiresAt<=System.currentTimeMillis() || recipient.fingerprint.equals(identity.fingerprint) || recipient.upload<=0 || recipient.clients>=3 || !node.isSharing() || node.measuredUpload()<=0){result.completeExceptionally(new IOException("Объявление устройства недоступно"));return;}
            Invitation established=invitations.get(recipient.fingerprint);if(established!=null){result.complete(established);return;}
            if(!background && pending!=null && pending.background){
                Pair previous=pending;
                if(previous.target.equals(recipient.fingerprint)){
                    CompletableFuture<Invitation> original=previous.completion;previous.completion=result;previous.background=false;stage=previous.remote==null?"WAITING_REPLY":"CONNECTING";
                    result.whenComplete((invitation,error)->{if(error==null)original.complete(invitation);else original.completeExceptionally(error);});return;
                }
                pending=null;dispose(previous,"Резервное сопряжение уступило выбранному устройству");
            }
            if(background && active.size()>=2){result.completeExceptionally(new IOException("Резервные связи уже установлены"));return;}
            if(pending!=null || active.size()>=3){result.completeExceptionally(new IOException("Сопряжение уже выполняется или нет свободного места"));return;}
            Pair pair=new Pair(recipient.fingerprint,result,background?Math.min(timeoutMillis,BACKUP_TIMEOUT_MILLIS):timeoutMillis);pair.background=background;pending=pair;setStage(pair,"GATHERING");
            try{create(pair);pair.transport.createOffer();}catch(Exception|LinkageError failure){fail(pair,"Не удалось запустить защищённое сопряжение");}
        },result);
        result.whenComplete((invitation,error)->{if(result.isCancelled())submit(()->{
            if(pending!=null && pending.completion==result){Pair cancelled=pending;pending=null;retireDeferred(cancelled.target);setStage(cancelled,"CANCELLED");dispose(cancelled,"Сопряжение отменено");}
        });});return result;
    }
    void cancelPending(String fingerprint){submit(()->{retireDeferred(fingerprint);if(pending!=null && pending.target.equals(fingerprint)){Pair cancelled=pending;pending=null;setStage(cancelled,"CANCELLED");dispose(cancelled,"Сопряжение отменено");}});}
    private void retireDeferred(String fingerprint){
        Iterator<Map.Entry<String,DeferredSignal>> waiting=deferred.entrySet().iterator();
        while(waiting.hasNext()){Map.Entry<String,DeferredSignal> entry=waiting.next();if(entry.getValue().code().fingerprint.equals(fingerprint)){remember(entry.getKey(),entry.getValue().signalExpiresAt());waiting.remove();}}
    }
    private void create(Pair pair)throws IOException {
        pair.transport=factory.create(node.port(),new TransportListener(){
            public void description(String type,String sdp){submit(()->{
                if(closed || pair.disposed || pending!=pair)return;
                try{
                    long now=System.currentTimeMillis();Invitation own=new Invitation("127.0.0.1",node.port(),identity.fingerprint,pair.token);
                    PairingCode code;if("offer".equals(type)){code=PairingCode.offer(identity,sdp,own,now);pair.offer=code;}else if("answer".equals(type))code=PairingCode.answer(identity,sdp,own,pair.offer,now);else throw new IOException("Pairing description type");
                    DiscoveryPresence recipient=discovery.presence(pair.target);if(recipient==null)throw new IOException("Recipient expired");
                    discoveryIdentity.presence(node.country,node.clientCount(),node.measuredUpload(),now);
                    DiscoverySignal signal=discoveryIdentity.seal(recipient,code.encode(),now);pair.signals.add(signal.id);discovery.publishSignal(signal);
                    setStage(pair,"offer".equals(type)?"WAITING_REPLY":"CONNECTING");
                }catch(Exception failure){fail(pair,"Не удалось передать защищённое описание соединения");}
            });}
            public void state(String state){submit(()->{
                if(closed || pair.disposed)return;
                if("CONNECTED".equals(state)){
                    if(pair.activated)return;if(pair.remote==null || pair.registration==null || System.nanoTime()>=pair.deadline){fail(pair,"Сопряжение истекло");return;}
                    pair.activated=true;active.put(pair.target,pair);invitations.put(pair.target,pair.remote.invitation);if(pending==pair)pending=null;setStage(pair,"CONNECTED");pair.completion.complete(pair.remote.invitation);discovery.refresh();
                }else if("FAILED".equals(state) || "CLOSED".equals(state) || "DISCONNECTED".equals(state))fail(pair,"Защищённое соединение потеряно");
            });}
            public void failed(String reason){submit(()->fail(pair,"Не удалось установить прямой защищённый канал"));}
        });
    }
    private void signalsChanged(){submit(this::processSignals);}
    private void processSignals(){
        if(!node.isSharing() || node.measuredUpload()<=0)return;long now=System.currentTimeMillis();handled.values().removeIf(expiry->expiry<=now);
        // Opening an encrypted signal consumes its replay nonce. Keep a bounded verified code while
        // capacity, another pairing, a still live predecessor or the rate limit prevents acceptance.
        for(Map.Entry<String,DeferredSignal> entry:new ArrayList<>(deferred.entrySet())){
            if(closed)return;DeferredSignal waiting=entry.getValue();
            if(waiting.expiresAt()<=now){deferred.remove(entry.getKey());remember(entry.getKey(),waiting.signalExpiresAt());continue;}
            if(discovery.presence(waiting.code().fingerprint)==null)continue;
            try{if(accept(waiting.code())==Acceptance.DEFERRED)continue;}
            catch(IOException|RuntimeException invalid){/* Verified content can still expire or fail correlation. */}
            deferred.remove(entry.getKey());remember(entry.getKey(),waiting.signalExpiresAt());
        }
        for(DiscoverySignal signal:discovery.signals()){
            String key=signal.senderFingerprint+":"+signal.id;
            if(closed || handled.containsKey(key) || deferred.containsKey(key) || signal.senderFingerprint.equals(identity.fingerprint) || discovery.presence(signal.senderFingerprint)==null)continue;
            if(pending!=null && !pending.background && !pending.target.equals(signal.senderFingerprint))continue;
            if(deferred.size()>=128)continue; // Never consume an encrypted nonce without room for a deferral.
            try{
                String text=discoveryIdentity.open(signal,now);PairingCode code=PairingCode.parseAndVerify(text,now);
                if(!code.fingerprint.equals(signal.senderFingerprint))throw new IOException("Signaling identity mismatch");
                if(accept(code)==Acceptance.DEFERRED)deferred.put(key,new DeferredSignal(code,signal.expiresAt));
                else remember(key,signal.expiresAt);
            }catch(IOException|RuntimeException invalid){remember(key,signal.expiresAt);}
        }
    }
    private void remember(String key,long expires){if(handled.size()>=512)handled.remove(handled.keySet().iterator().next());handled.put(key,expires);}
    private Acceptance accept(PairingCode code)throws IOException {
        long now=System.currentTimeMillis();
        if(code.expiresAt<=now)return Acceptance.REJECTED;
        if("offer".equals(code.type)){
            if(active.containsKey(code.fingerprint) || active.size()>=3)return Acceptance.DEFERRED;
            boolean replaceOther=pending!=null && pending.background && !pending.target.equals(code.fingerprint);
            if(pending!=null && !replaceOther && (!pending.target.equals(code.fingerprint) || code.fingerprint.compareTo(identity.fingerprint)>=0))return Acceptance.DEFERRED;
            if((pending==null || replaceOther) && (now-lastIncoming<10_000 || active.size()>=2))return Acceptance.DEFERRED;
            // Claim replay ownership only after all transient refusal conditions have cleared.
            if(!replay.claim(code,now))return Acceptance.REJECTED;
            CompletableFuture<Invitation> future=new CompletableFuture<>();boolean background=true;
            if(pending!=null){
                if(replaceOther){
                    Pair old=pending;pending=null;dispose(old,"Резервное сопряжение уступило входящему устройству");
                }else{
                    Pair old=pending;pending=null;future=old.completion;background=old.background;disposeTransport(old,false);
                }
            }
            lastIncoming=now;Pair pair=new Pair(code.fingerprint,future,Math.min(background?Math.min(timeoutMillis,BACKUP_TIMEOUT_MILLIS):timeoutMillis,Math.max(1,code.expiresAt-now)));pair.background=background;pair.remote=code;pair.offer=code;pending=pair;setStage(pair,"GATHERING");
            try{create(pair);register(pair);pair.transport.acceptOffer(code.sdp);}catch(Exception|LinkageError failure){fail(pair,"Не удалось принять защищённое сопряжение");}
        }else{
            Pair pair=pending;if(pair==null || pair.offer==null || pair.remote!=null || pair.consumed || !pair.target.equals(code.fingerprint))return Acceptance.REJECTED;
            PairingCode.verifyAnswer(code,pair.offer,now);if(!replay.claim(code,now))return Acceptance.REJECTED;
            pair.consumed=true;pair.remote=code;setStage(pair,"CONNECTING");
            try{register(pair);pair.transport.acceptAnswer(code.sdp);}catch(Exception|LinkageError failure){fail(pair,"Не удалось подтвердить защищённое сопряжение");}
        }
        return Acceptance.ACCEPTED;
    }
    private void register(Pair pair)throws IOException {pair.registration=node.registerPeerTransport(pair.remote.fingerprint,pair.transport::openStream,pair.token);}
    /** A live paired transport's chat stream, or null when this identity has no P2P session. */
    Socket openChatStream(String fingerprint){
        if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}"))return null;
        Pair pair=active.get(fingerprint);
        if(pair==null||pair.disposed||pair.transport==null||!pair.transport.isConnected())return null;
        try{return pair.transport.openChatStream();}catch(IOException unavailable){return null;}
    }
    /**
     * A chat contact may exist without a sharing session. If the device is announced in the
     * catalog, ask the same signed pairing handshake to open one; delivery retries later.
     */
    void requestChatSession(String fingerprint){
        if(fingerprint==null||!fingerprint.matches("[0-9a-f]{64}"))return;
        submit(()->{
            if(closed||active.containsKey(fingerprint))return;
            DiscoveryPresence presence=discovery.presence(fingerprint);
            if(presence==null||presence.expiresAt<=System.currentTimeMillis())return;
            if(pending!=null&&!fingerprint.equals(pending.target))return;
            connect(presence,true).exceptionally(error->null);
        });
    }
    private void fail(Pair pair,String message){if(pair.disposed)return;if(pending==pair)pending=null;if(!closed)setStage(pair,"FAILED");dispose(pair,message);}
    private void dispose(Pair pair,String message){if(pair.disposed)return;pair.completion.completeExceptionally(new IOException(message));disposeTransport(pair,true);}
    private void disposeTransport(Pair pair,boolean notify){
        if(pair.disposed)return;pair.disposed=true;active.remove(pair.target,pair);invitations.remove(pair.target);discovery.cancelSignals(pair.signals);
        try{if(pair.registration!=null)pair.registration.close();if(notify && pair.activated)listener.unavailable(pair.target);}
        finally{if(pair.transport!=null)pair.transport.close();}
    }
    private void submit(Runnable action){submit(action,null);}
    private void submit(Runnable action,CompletableFuture<?> rejected){
        if(closed){if(rejected!=null)rejected.completeExceptionally(new IOException("Pairing closed"));return;}
        try{serial.execute(()->{if(!closed)action.run();else if(rejected!=null)rejected.completeExceptionally(new IOException("Pairing closed"));});}
        catch(RejectedExecutionException stopped){if(rejected!=null)rejected.completeExceptionally(new IOException("Pairing closed"));}
    }
    @Override public void close(){
        synchronized(this){if(closed)return;closed=true;stage="CLOSED";}discovery.clearSignalListener(signalCallback);timer.shutdownNow();
        serial.execute(()->{if(pending!=null){Pair old=pending;pending=null;dispose(old,"Сопряжение остановлено");}for(Pair pair:active.values().toArray(new Pair[0]))dispose(pair,"Сопряжение остановлено");invitations.clear();handled.clear();deferred.clear();});serial.shutdown();
    }
}
