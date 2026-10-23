package org.uzel.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.uzel.core.*;

/** Ephemeral signed catalog and recipient-encrypted signaling. Never persists SDP or invitations. */
final class AutoDiscoveryManager implements AutoCloseable {
    interface Listener { void catalog(List<DiscoveryPresence> values); void failed(String fingerprint); }
    private final Node node;
    private final PhysicalNetwork physical;
    private final PairingManager pairing;
    private final DiscoveryIdentity identity;
    private final TlsIdentity tlsIdentity;
    private final DirectoryContacts directoryContacts;
    private final DirectoryFailover failover=new DirectoryFailover();
    private volatile Socket activeDirectorySocket;
    private final DiscoveryStore store=new DiscoveryStore();
    private final Supplier<List<Invitation>> contacts;
    private final Listener listener;
    private final InetAddress hostingAddress;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private volatile HttpURLConnection activeRequest;
    private final Map<String,Long> handled=new LinkedHashMap<>();
    private static final int MAX_DEFERRED_SIGNALS=128;
    private static final class IncomingSignal {
        final DiscoverySignal signal;final String code;final PairingCode verified;
        IncomingSignal(DiscoverySignal signal,String code,PairingCode verified){this.signal=signal;this.code=code;this.verified=verified;}
        String key(){return signal.senderFingerprint+":"+signal.id;}
        long expiresAt(){return Math.min(signal.expiresAt,verified.expiresAt);}
    }
    // Opening an encrypted signal consumes DiscoveryIdentity's replay entry. Keep its plaintext
    // only in this bounded memory cache until pairing admits it or its signed lifetime ends.
    private final Map<String,IncomingSignal> deferredSignals=new LinkedHashMap<>();
    private IncomingSignal incomingSignal;
    private int incomingCursor;
    private volatile boolean closed,hosting;
    private volatile String url,state="STARTING",pending="";
    private final AtomicLong refreshRequests=new AtomicLong();
    private volatile long completedRefresh;
    long completedRefresh(){return completedRefresh;}
    long requestRefresh(){
        long request=refreshRequests.incrementAndGet();
        execute(()->{cycle();completedRefresh=request;});
        return request;
    }
    private volatile BootstrapHttpServer server;
    private String ownFingerprint;
    private long publishedAt,pendingUntil,lastIncoming;
    private long lastBootstrapAttempt,lastBackupAttempt,extendedHttpRetry;
    private boolean backgroundPending;
    private final Map<String,Long> legacyPeers=new HashMap<>();
    private final Map<String,Long> backupRetryAt=new HashMap<>();
    private final Map<String,Set<String>> ownSignals=new HashMap<>();
    private final Map<String,Long> retiredSignals=new ConcurrentHashMap<>();
    private volatile String lastSource="";
    private static final class UnsupportedDiscoveryVersion extends IOException {}
    String source(){return lastSource;}
    int backupPeerCount(){return contacts.get().size();}
    private final AtomicLong attempts=new AtomicLong(),cancellations=new AtomicLong();
    private volatile long pendingAttempt;
    private int cursor;

    AutoDiscoveryManager(Context context,Node node,PhysicalNetwork physical,PairingManager pairing,
                         String url,boolean hosting,Supplier<List<Invitation>> contacts,Listener listener)throws Exception {
        this.node=node;this.physical=physical;this.pairing=pairing;this.url=validateUrl(url);
        this.hosting=hosting;this.contacts=contacts;this.listener=listener;
        tlsIdentity=DeviceIdentity.load();identity=new DiscoveryIdentity(tlsIdentity);
        directoryContacts=new DirectoryContacts(context);
        ownFingerprint=node.invitation("127.0.0.1").fingerprint;
        LinkProperties properties=context.getSystemService(ConnectivityManager.class).getLinkProperties(physical.network);
        InetAddress bind=null;
        if(properties!=null)for(LinkAddress value:properties.getLinkAddresses()){
            InetAddress candidate=value.getAddress();
            if(candidate.isLoopbackAddress()||candidate.isAnyLocalAddress()||candidate.isLinkLocalAddress())continue;
            if(bind==null||candidate instanceof Inet4Address)bind=candidate;
            if(candidate instanceof Inet4Address)break;
        }
        hostingAddress=bind;
        node.setDiscoveryExchange(frame->{
            if(!enabled())throw new IOException("Discovery disabled");
            byte[] response=exchangeIncoming(frame);
            directoryContacts.merge(store.contacts(System.currentTimeMillis()));
            return response;
        });
        worker.scheduleWithFixedDelay(this::cycle,0,3,TimeUnit.SECONDS);
    }
    static String validateUrl(String value){
        if(value==null)throw new IllegalArgumentException("URL");
        value=value.trim();if(value.isEmpty())return "";
        try{
            URI parsed=new URI(value);
            if(!"https".equalsIgnoreCase(parsed.getScheme())||parsed.getHost()==null||parsed.getUserInfo()!=null||parsed.getFragment()!=null||parsed.getQuery()!=null||parsed.getPort()==0||parsed.getPort()>65535||value.length()>2048)
                throw new IllegalArgumentException("HTTPS URL required");
            String path=parsed.getPath();
            if(path==null||path.isEmpty()||"/".equals(path))return value.replaceAll("/+$","")+"/v1/exchange";
            if(!"/v1/exchange".equals(path))throw new IllegalArgumentException("Exchange endpoint required");
            return value;
        }catch(URISyntaxException e){throw new IllegalArgumentException("URL",e);}
    }
    String state(){return state;}
    boolean isPairing(){return !pending.isEmpty();}
    boolean hosting(){return server!=null;}
    int port(){BootstrapHttpServer current=server;return current==null?0:current.port();}
    void configure(String value,boolean host){url=validateUrl(value);hosting=host;cancelRequest();execute(this::cycle);}
    private boolean enabled(){return !closed&&node.isSharing()&&(!url.isEmpty()||hosting||!contacts.get().isEmpty()||!directoryContacts.list().isEmpty());}
    void addDirectoryContact(String code)throws IOException{directoryContacts.add(code);execute(this::cycle);}
    int directoryContactCount(){return directoryContacts.list().size();}
    void allowPrivateDirectories(boolean enabled){directoryContacts.setAllowPrivate(enabled);cancelRequest();execute(this::cycle);}
    private void forgetSignals(String fingerprint){
        Set<String> ids=ownSignals.remove(fingerprint);if(ids==null)return;
        for(DiscoverySignal signal:store.signals(System.currentTimeMillis()))if(signal.senderFingerprint.equals(ownFingerprint)&&ids.contains(signal.id)){
            if(retiredSignals.size()>=128)retiredSignals.remove(retiredSignals.keySet().iterator().next());
            retiredSignals.put(signal.id,signal.expiresAt);
        }
        store.removeSignals(ownFingerprint,ids);
    }
    private void removeRetiredSignals(){retiredSignals.values().removeIf(expiry->expiry<=System.currentTimeMillis());store.removeSignals(ownFingerprint,retiredSignals.keySet());}
    private byte[] exchangeIncoming(byte[] frame)throws IOException {
        retiredSignals.values().removeIf(expiry->expiry<=System.currentTimeMillis());
        return exchangeFiltered(store,frame,ownFingerprint,retiredSignals.keySet(),System.currentTimeMillis());
    }
    static byte[] exchangeFiltered(DiscoveryStore cache,byte[] frame,String sender,Collection<String> retired,long now)throws IOException {
        synchronized(cache){
            cache.ingestFrame(frame,now);cache.removeSignals(sender,retired);
            return new DataInputStream(new ByteArrayInputStream(frame)).readInt()==0x555a4432?cache.exportFrameWithContacts(now):cache.exportFrame(now);
        }
    }
    void cancel(String fingerprint){cancellations.incrementAndGet();execute(()->{retireIncoming(fingerprint);if(pending.equals(fingerprint)){pending="";pendingUntil=0;backgroundPending=false;pendingAttempt=attempts.incrementAndGet();}forgetSignals(fingerprint);pairing.cancelTargeted(fingerprint);});}
    private void retireIncoming(String fingerprint){
        Iterator<IncomingSignal> values=deferredSignals.values().iterator();
        while(values.hasNext()){IncomingSignal value=values.next();if(value.signal.senderFingerprint.equals(fingerprint)){remember(value.signal);values.remove();}}
    }
    private void paired(String fingerprint,long attempt){execute(()->{if(pending.equals(fingerprint)&&pendingAttempt==attempt){pending="";pendingUntil=0;backgroundPending=false;forgetSignals(fingerprint);state="ONLINE";}});}
    void connect(DiscoveryPresence recipient){long cancellation=cancellations.get();execute(()->{
        if(cancellation!=cancellations.get())return;
        if(backgroundPending){
            if(pending.equals(recipient.fingerprint)){backgroundPending=false;return;}
            String old=pending;pending="";pendingAttempt=attempts.incrementAndGet();backgroundPending=false;forgetSignals(old);pairing.cancelTargeted(old);
        }
        long now=System.currentTimeMillis();
        if(!enabled()||!pending.isEmpty()||recipient.expiresAt<=now||recipient.clients>=3||recipient.upload<=0){listener.failed(recipient.fingerprint);return;}
        pending=recipient.fingerprint;pendingAttempt=attempts.incrementAndGet();pendingUntil=now+180_000;state="PAIRING";
        pairing.offerTo(recipient.fingerprint,sink(recipient,pendingAttempt));
    });}
    private PairingManager.DescriptionSink sink(DiscoveryPresence recipient,long attempt){
        return new PairingManager.DescriptionSink(){
            public void description(String code){execute(()->{
                if(!enabled()||!pending.equals(recipient.fingerprint)||pendingAttempt!=attempt)return;
                try{
                    long now=System.currentTimeMillis();
                    // Gathering may outlive the original advertisement. Resolve only a fresh signed
                    // record for the pinned identity; never extend an old record or reuse its key.
                    DiscoveryPresence current=currentRecipient(store,recipient.fingerprint,now);
                    store.accept(identity.presence(node.country,node.clientCount(),node.measuredUpload(),now),now);publishedAt=now;
                    DiscoverySignal signal=identity.seal(current,code,now);removeRetiredSignals();store.accept(signal,now);
                    ownSignals.computeIfAbsent(current.fingerprint,key->new HashSet<>()).add(signal.id);cycle();
                }
                catch(IOException failure){AutoDiscoveryManager.this.failed(recipient.fingerprint,attempt);}
            });}
            public void failed(){execute(()->AutoDiscoveryManager.this.failed(recipient.fingerprint,attempt));}
            public void connected(){paired(recipient.fingerprint,attempt);}
        };
    }
    static DiscoveryPresence currentRecipient(DiscoveryStore store,String fingerprint,long now)throws IOException {
        for(DiscoveryPresence value:store.presences(now))if(value.fingerprint.equals(fingerprint))return value;
        throw new IOException("Recipient announcement unavailable");
    }
    private void failed(String fingerprint,long attempt){
        if(pending.equals(fingerprint)&&pendingAttempt==attempt){boolean background=backgroundPending;pending="";pendingUntil=0;backgroundPending=false;forgetSignals(fingerprint);if(background){backupRetryAt.put(fingerprint,System.currentTimeMillis()+60_000);lastBackupAttempt=0;}else{state="OFFLINE";listener.failed(fingerprint);}}
    }
    private void cycle(){
        if(closed)return;
        try{
            updateHosting();
            if(!enabled()){if(!pending.isEmpty()){String old=pending;pending="";pairing.cancelTargeted(old);listener.failed(old);}state="NOT_CONFIGURED";return;}
            long now=System.currentTimeMillis();
            if(!pending.isEmpty()&&now>pendingUntil){String old=pending;pairing.cancel();failed(old,pendingAttempt);}
            if(node.measuredUpload()<=0){state="STARTING";return;}
            if(now-publishedAt>30_000){
                store.accept(identity.presence(node.country,node.clientCount(),node.measuredUpload(),now),now);publishedAt=now;
            }
            processSignals(now);
            boolean exchanged=false;
            for(FederationContact contact:directoryContacts.list())store.accept(contact,now);
            List<Invitation> peers=contacts.get();
            for(int attempt=0;attempt<Math.min(2,peers.size())&&!closed;attempt++){
                Invitation peer=peers.get(Math.floorMod(cursor++,peers.size()));
                try{
                    byte[] response;
                    if(legacyPeers.getOrDefault(peer.fingerprint,0L)>now)response=node.exchangeDiscovery(peer,store.exportFrame(now));
                    else try{response=node.exchangeDiscovery(peer,store.exportFrameWithContacts(now));}
                    catch(IOException unsupported){response=node.exchangeDiscovery(peer,store.exportFrame(System.currentTimeMillis()));legacyPeers.put(peer.fingerprint,now+60_000);}
                    if(closed)return;store.ingestFrame(response,System.currentTimeMillis());removeRetiredSignals();
                    directoryContacts.merge(store.contacts(System.currentTimeMillis()));exchanged=true;lastSource="PEER";
                }catch(IOException ignored){}
            }
            Set<String> livePeerIds=new HashSet<>();for(Invitation peer:peers)livePeerIds.add(peer.fingerprint);
            legacyPeers.keySet().retainAll(livePeerIds);
            Map<String,FederationContact> directoryServers=new LinkedHashMap<>();
            List<String> candidates=new ArrayList<>();
            for(FederationContact contact:directoryContacts.list()){directoryServers.put(contact.fingerprint,contact);candidates.add(contact.fingerprint);}
            String endpoint=url;if(!endpoint.isEmpty())candidates.add(endpoint);
            int tried=0;boolean peerAvailable=exchanged;
            for(String key:failover.order(candidates,android.os.SystemClock.elapsedRealtime())){
                if(peerAvailable && now-lastBootstrapAttempt<30_000)break;
                if(closed||tried++>=3)break;
                lastBootstrapAttempt=now;
                try{
                    FederationContact contact=directoryServers.get(key);
                    byte[] response;
                    if(contact==null){
                        if(now>=extendedHttpRetry)try{response=post(key,store.exportFrameWithContacts(System.currentTimeMillis()));}
                        catch(UnsupportedDiscoveryVersion unsupported){extendedHttpRetry=now+60_000;response=post(key,store.exportFrame(System.currentTimeMillis()));}
                        else response=post(key,store.exportFrame(System.currentTimeMillis()));
                    }
                    else response=exchangeDirectory(contact);
                    if(closed)return;store.ingestFrame(response,System.currentTimeMillis());removeRetiredSignals();
                    directoryContacts.merge(store.contacts(System.currentTimeMillis()));
                    if(!peerAvailable)lastSource=contact==null?"BOOTSTRAP":"DIRECTORY";
                    failover.succeeded(key);exchanged=true;break;
                }catch(IOException unavailable){failover.failed(key,android.os.SystemClock.elapsedRealtime());}
                if(peerAvailable)break;
            }
            if(closed)return;
            processSignals(System.currentTimeMillis());
            listener.catalog(store.presences(System.currentTimeMillis()));
            state=!pending.isEmpty()&&!backgroundPending?"PAIRING":exchanged||server!=null?"ONLINE":"OFFLINE";
            if(!exchanged)lastSource="";
            if(exchanged && pending.isEmpty() && incomingSignal==null && now-lastBackupAttempt>=15_000){
                Set<String> connected=new HashSet<>();for(Invitation peer:contacts.get())connected.add(peer.fingerprint);
                backupRetryAt.entrySet().removeIf(e->e.getValue()<=System.currentTimeMillis());
                DiscoveryPresence backup=BackupPeerSelector.next(ownFingerprint,store.presences(System.currentTimeMillis()),connected,System.currentTimeMillis(),backupRetryAt.keySet());
                if(backup!=null){lastBackupAttempt=now;backgroundPending=true;pending=backup.fingerprint;pendingAttempt=attempts.incrementAndGet();pendingUntil=now+120_000;pairing.offerTo(backup.fingerprint,sink(backup,pendingAttempt));}
            }
        }catch(Exception failure){if(!closed)state="OFFLINE";}
    }
    private void processSignals(long now)throws IOException {
        handled.entrySet().removeIf(e->e.getValue()<=now);
        deferredSignals.values().removeIf(value->value.expiresAt()<=now);
        if(incomingSignal!=null)return; // At most one admission queued on PairingManager's serial worker.
        Map<String,DiscoveryPresence> catalog=new HashMap<>();
        for(DiscoveryPresence value:store.presences(now))catalog.put(value.fingerprint,value);
        List<DiscoverySignal> signals=new ArrayList<>(store.signalsFor(ownFingerprint,now));
        if(!signals.isEmpty())Collections.rotate(signals,-Math.floorMod(incomingCursor++,signals.size()));
        for(DiscoverySignal signal:signals){
            String key=signal.senderFingerprint+":"+signal.id;
            if(closed||!enabled()||handled.containsKey(key))continue;
            DiscoveryPresence sender=catalog.get(signal.senderFingerprint);
            if(sender==null||sender.fingerprint.equals(ownFingerprint))continue;
            if(!pending.isEmpty()&&!pending.equals(sender.fingerprint)&&!backgroundPending)continue;
            if(pending.isEmpty()&&now-lastIncoming<10_000)continue;
            IncomingSignal incoming=deferredSignals.get(key);
            if(incoming==null){
                if(deferredSignals.size()>=MAX_DEFERRED_SIGNALS)continue;
                try{
                    String code=identity.open(signal,now);PairingCode verified=PairingCode.parseAndVerify(code,now);
                    if(!verified.fingerprint.equals(sender.fingerprint))throw new IOException("Signal sender identity");
                    incoming=new IncomingSignal(signal,code,verified);deferredSignals.put(key,incoming);
                }catch(IOException invalid){remember(signal);continue;}
            }
            boolean offer="offer".equals(incoming.verified.type);
            if(offer&&now-lastIncoming<10_000)continue;
            if(!offer&&!pending.equals(sender.fingerprint)){deferredSignals.remove(key);remember(signal);continue;}
            final IncomingSignal request=incoming;
            final long generation=cancellations.get(),previousAttempt=pendingAttempt;
            final String previousPending=pending;
            final boolean previousBackground=backgroundPending;
            final long attempt=offer?attempts.incrementAndGet():pendingAttempt;
            incomingSignal=request;
            final PairingManager.DescriptionSink admissionSink=sink(sender,attempt);
            pairing.acceptFrom(request.code,sender.fingerprint,admissionSink,previousBackground?previousPending:null,
                    ()->!closed&&cancellations.get()==generation&&pendingAttempt==previousAttempt,
                    result->execute(()->{
                        if(incomingSignal!=request)return;
                        incomingSignal=null;
                        if(!enabled()||cancellations.get()!=generation||pendingAttempt!=previousAttempt){
                            if(result==PairingManager.Admission.ACCEPTED)pairing.cancelTargeted(sender.fingerprint,admissionSink);
                            return;
                        }
                        if(request.expiresAt()<=System.currentTimeMillis()){
                            deferredSignals.remove(request.key());remember(request.signal);
                            if(result==PairingManager.Admission.ACCEPTED)pairing.cancelTargeted(sender.fingerprint,admissionSink);
                            return;
                        }
                        if(result==PairingManager.Admission.DEFERRED)return;
                        deferredSignals.remove(request.key());remember(request.signal);
                        if(result!=PairingManager.Admission.ACCEPTED)return;
                        if(offer){
                            if(previousBackground&&!previousPending.equals(sender.fingerprint))forgetSignals(previousPending);
                            backgroundPending=false;lastIncoming=System.currentTimeMillis();pending=sender.fingerprint;
                            pendingAttempt=attempt;pendingUntil=Math.min(lastIncoming+180_000,request.verified.expiresAt);
                        }
                        if(!backgroundPending)state="PAIRING";
                    }));
            return;
        }
    }
    private void remember(DiscoverySignal signal){
        if(handled.size()>=512)handled.remove(handled.keySet().iterator().next());
        handled.put(signal.senderFingerprint+":"+signal.id,signal.expiresAt);
    }
    private byte[] post(String endpoint,byte[] request)throws IOException {
        HttpURLConnection connection=physical.http(endpoint);
        activeRequest=connection;
        ScheduledFuture<?> deadline=null;
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try{
            if(closed)throw new InterruptedIOException("Closed");
            deadline=watchdog.schedule(connection::disconnect,5,TimeUnit.SECONDS);
            connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/octet-stream");
            connection.setFixedLengthStreamingMode(request.length);
            try(OutputStream out=connection.getOutputStream()){out.write(request);}
            int responseCode=connection.getResponseCode();
            if(responseCode==400)throw new UnsupportedDiscoveryVersion();
            if(responseCode!=200)throw new IOException("Bootstrap unavailable");
            if(connection.getContentLengthLong()>DiscoveryStore.MAX_FRAME)throw new IOException("Frame too large");
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] chunk=new byte[4096];int count;
                while((count=in.read(chunk))!=-1){if(closed||System.nanoTime()>until)throw new InterruptedIOException("Exchange deadline");if(out.size()+count>DiscoveryStore.MAX_FRAME)throw new IOException("Frame too large");out.write(chunk,0,count);}
                return out.toByteArray();
            }
        }finally{if(deadline!=null)deadline.cancel(false);if(activeRequest==connection)activeRequest=null;connection.disconnect();}
    }
    private byte[] exchangeDirectory(FederationContact contact)throws IOException {
        InetSocketAddress address=contact.address(physical.resolve(contact.host),directoryContacts.allowPrivate());
        try(Socket socket=physical.open(address.getAddress(),address.getPort())){
            activeDirectorySocket=socket;
            if(closed)throw new InterruptedIOException("Closed");
            ScheduledFuture<?> deadline=watchdog.schedule(()->{try{socket.close();}catch(IOException ignored){}},12,TimeUnit.SECONDS);
            try{
                FederationExchange.Result result=FederationExchange.exchange(tlsIdentity,socket,contact,store.exportFrame(System.currentTimeMillis()));
                if(closed)throw new InterruptedIOException("Closed");
                directoryContacts.merge(result.contacts);
                return result.discoveryFrame;
            }finally{deadline.cancel(false);activeDirectorySocket=null;}
        }
    }
    private void updateHosting()throws IOException {
        if((!hosting||!node.isSharing())&&server!=null){BootstrapHttpServer old=server;server=null;old.close();}
        if(hosting&&node.isSharing()&&server==null){
            if(hostingAddress==null)throw new IOException("No physical address");
            server=new BootstrapHttpServer(hostingAddress,39282,frame->{
                if(!enabled()||!hosting)throw new IOException("Discovery disabled");
                return exchangeIncoming(frame);
            });
        }
    }
    private void cancelRequest(){Socket socket=activeDirectorySocket;if(socket!=null)try{socket.close();}catch(IOException ignored){}HttpURLConnection current=activeRequest;if(current!=null)try{watchdog.execute(current::disconnect);}catch(RejectedExecutionException ignored){}}
    private void execute(Runnable task){if(!closed)try{worker.execute(()->{if(!closed)task.run();});}catch(RejectedExecutionException ignored){}}
    @Override public void close(){
        if(closed)return;closed=true;node.setDiscoveryExchange(null);cancelRequest();watchdog.shutdown();
        worker.execute(()->{BootstrapHttpServer old=server;server=null;if(old!=null)old.close();store.clear();handled.clear();deferredSignals.clear();incomingSignal=null;});
        worker.shutdown();
    }
}
