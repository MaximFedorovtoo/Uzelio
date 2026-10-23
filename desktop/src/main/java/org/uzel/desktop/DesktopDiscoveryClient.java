package org.uzel.desktop;

import org.uzel.core.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import java.util.function.Supplier;
import java.util.function.BiFunction;

/** HTTPS transport for the SAME signed discovery frames used by Android. A presence never authorizes a route. */
final class DesktopDiscoveryClient implements AutoCloseable {
    @FunctionalInterface interface Connections {HttpURLConnection open(URL url)throws IOException;}
    @FunctionalInterface interface Publisher {DiscoveryPresence current()throws IOException;}
    @FunctionalInterface interface ContactPublisher {FederationContact current()throws IOException;}
    private final String fingerprint,endpoint;
    private final Connections connections;
    private final long deadlineMillis;
    private final DiscoveryStore cache=new DiscoveryStore();
    private final ExecutorService peerWorker=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"uzelio-peer-discovery");thread.setDaemon(true);return thread;});
    private Node peerNode;
    private Supplier<List<Invitation>> peers=List::of;
    private ContactPublisher contactPublisher=()->null;
    private TlsIdentity federationIdentity;
    private DesktopDiscoveryContacts contactFile;
    private CompletableFuture<Boolean> httpPending,peerPending;
    private long lastUpgradeProbe=System.nanoTime(),lastSuccess,httpRetryAfter;
    private int httpFailures;
    private volatile int livePeerExchanges;
    private final Map<String,Long> legacyPeers=new ConcurrentHashMap<>();
    private final DirectoryFailover contactFailover=new DirectoryFailover();
    private int contactCursor;
    private BiFunction<FederationContact,Long,InetSocketAddress> contactResolver=(contact,deadline)->{
        try{return FederationResolver.resolvePublic(contact,deadline);}catch(IOException failure){throw new UncheckedIOException(failure);}
    };
    private final Map<String,DiscoverySignal> outgoing=new LinkedHashMap<>();
    private final Map<String,Long> cancelledSignals=new LinkedHashMap<>();
    private Runnable signalListener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"uzelio-bootstrap-discovery");thread.setDaemon(true);return thread;});
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"uzelio-bootstrap-deadline");thread.setDaemon(true);return thread;});
    private Publisher publisher;
    private HttpURLConnection activeRequest;
    private Socket activeFederation;
    private long generation,lastAttempt;
    private boolean closed,paused;
    private String state="NOT_STARTED",detail="";

    static DesktopDiscoveryClient configured(Path root,String fingerprint){
        try{
            Properties settings=new Properties();Path file=root.resolve("discovery.properties");
            if(Files.exists(file)){if(Files.size(file)>8192)throw new IOException("Discovery settings size");try(InputStream input=Files.newInputStream(file)){settings.load(input);}}
            String endpoint=settings.getProperty("bootstrapUrl");if(endpoint==null)endpoint=defaultEndpoint();
            DesktopDiscoveryClient client=new DesktopDiscoveryClient(fingerprint,endpoint,url->(HttpURLConnection)url.openConnection(Proxy.NO_PROXY),()->null,12_000);
            try{client.setContactFile(root.resolve("discovery-contacts.bin"));}catch(IOException invalidHints){client.detail="Сохранённые резервные контакты недоступны";}return client;
        }catch(IOException|RuntimeException invalid){
            DesktopDiscoveryClient disabled=disabled(fingerprint);disabled.detail="Не удалось прочитать настройки каталога объявлений";return disabled;
        }
    }
    static DesktopDiscoveryClient disabled(String fingerprint){return new DesktopDiscoveryClient(fingerprint,"",url->{throw new IOException("Not configured");},()->null,12_000);}
    static String defaultEndpoint()throws IOException {
        try(InputStream input=DesktopDiscoveryClient.class.getResourceAsStream("/bootstrap_config.xml")){
            if(input==null)throw new IOException("Missing shared discovery configuration");byte[] bytes=input.readNBytes(8193);if(bytes.length>8192)throw new IOException("Discovery configuration size");
            Matcher found=Pattern.compile("<string\\s+name=[\"']bootstrap_default_url[\"'][^>]*>([^<]+)</string>").matcher(new String(bytes,StandardCharsets.UTF_8));
            if(!found.find())throw new IOException("Missing discovery endpoint");return validateEndpoint(found.group(1));
        }
    }
    static String validateEndpoint(String value){
        if(value==null)throw new IllegalArgumentException("Discovery endpoint");value=value.trim();if(value.isEmpty())return "";
        try{
            URI uri=new URI(value);String path=uri.getPath();
            if(value.length()>2048 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || uri.getPort()==0 || uri.getPort()>65535)
                throw new IllegalArgumentException("HTTPS discovery endpoint required");
            if(path==null || path.isEmpty() || path.equals("/"))return value.replaceAll("/+$","")+"/v1/exchange";
            if(!path.equals("/v1/exchange"))throw new IllegalArgumentException("Discovery exchange path required");return value;
        }catch(URISyntaxException invalid){throw new IllegalArgumentException("Discovery endpoint",invalid);}
    }
    DesktopDiscoveryClient(String fingerprint,String endpoint,Connections connections,Publisher publisher,long deadlineMillis){
        this.fingerprint=Objects.requireNonNull(fingerprint);this.endpoint=validateEndpoint(endpoint);this.connections=Objects.requireNonNull(connections);this.publisher=Objects.requireNonNull(publisher);
        if(deadlineMillis<1 || deadlineMillis>20_000)throw new IllegalArgumentException("Discovery deadline");this.deadlineMillis=deadlineMillis;
        if(this.endpoint.isEmpty()){state="NOT_CONFIGURED";detail="Каталог объявлений не задан";}
    }
    synchronized void setPublisher(Publisher publisher){this.publisher=Objects.requireNonNull(publisher);}
    synchronized void setContactPublisher(ContactPublisher publisher){contactPublisher=Objects.requireNonNull(publisher);}
    synchronized void setFederationIdentity(TlsIdentity identity){federationIdentity=Objects.requireNonNull(identity);}
    synchronized void setContactResolver(BiFunction<FederationContact,Long,InetSocketAddress> resolver){contactResolver=Objects.requireNonNull(resolver);}
    synchronized void setContactFile(Path path)throws IOException {contactFile=new DesktopDiscoveryContacts(path);for(FederationContact contact:contactFile.load())cache.accept(contact,System.currentTimeMillis());}
    List<FederationContact> contacts(){return cache.contacts(System.currentTimeMillis());}
    int livePeerExchanges(){return livePeerExchanges;}
    synchronized void attachPeerExchange(Node node,Supplier<List<Invitation>> invitations){
        detachPeerExchange();peerNode=Objects.requireNonNull(node);peers=Objects.requireNonNull(invitations);
        long epoch=generation;
        node.setDiscoveryExchange(frame->{
            synchronized(this){if(!current(epoch) || peerNode!=node)throw new IOException("Discovery stopped");}
            refreshOwn();byte[] response;
            synchronized(this){if(!current(epoch) || peerNode!=node)throw new IOException("Discovery stopped");cache.ingestFrame(frame,System.currentTimeMillis());removeCancelledSignals();response=new DataInputStream(new ByteArrayInputStream(frame)).readInt()==0x555a4432?cache.exportFrameWithContacts(System.currentTimeMillis()):cache.exportFrame(System.currentTimeMillis());saveContacts();}
            received(epoch);return response;
        });
    }
    synchronized void detachPeerExchange(){if(peerNode!=null)peerNode.setDiscoveryExchange(null);peerNode=null;peers=List::of;livePeerExchanges=0;}
    synchronized void setSignalListener(Runnable listener){signalListener=listener;}
    synchronized void clearSignalListener(Runnable listener){if(signalListener==listener)signalListener=null;}
    DiscoveryPresence presence(String target){return presences().stream().filter(p->p.fingerprint.equals(target)).findFirst().orElse(null);}
    List<DiscoverySignal> signals(){return cache.signalsFor(fingerprint,System.currentTimeMillis());}
    void publishSignal(DiscoverySignal signal)throws IOException {
        long now=System.currentTimeMillis();DiscoverySignal checked=DiscoverySignal.parse(signal.encode(),now);
        synchronized(this){
            if(closed || paused || !checked.senderFingerprint.equals(fingerprint))throw new IOException("Signaling unavailable");
            outgoing.values().removeIf(value->value.expiresAt<=now);if(outgoing.size()>=8)throw new IOException("Signaling capacity");outgoing.put(checked.id,checked);
        }
        CompletableFuture<Boolean> exchange=refresh();exchange.whenComplete((result,failure)->refresh());
    }
    synchronized void cancelSignals(Collection<String> ids){
        for(String id:ids){DiscoverySignal removed=outgoing.remove(id);if(removed!=null){if(cancelledSignals.size()>=128)cancelledSignals.remove(cancelledSignals.keySet().iterator().next());cancelledSignals.put(id,removed.expiresAt);}}
        removeCancelledSignals();
    }
    private void removeCancelledSignals(){cancelledSignals.values().removeIf(expiry->expiry<=System.currentTimeMillis());cache.removeSignals(fingerprint,cancelledSignals.keySet());}
    synchronized CompletableFuture<Boolean> refresh(){
        return refresh(false);
    }
    private synchronized CompletableFuture<Boolean> refresh(boolean periodic){
        if(closed || paused)return CompletableFuture.completedFuture(false);
        long epoch=generation;lastAttempt=System.nanoTime();if(!state.equals("ONLINE") || System.nanoTime()-lastSuccess>TimeUnit.SECONDS.toNanos(10))state="SEARCHING";detail="";
        List<CompletableFuture<Boolean>> paths=new ArrayList<>();
        if(peerNode!=null){if(peerPending==null || peerPending.isDone())peerPending=CompletableFuture.supplyAsync(()->exchangePeers(epoch),peerWorker);paths.add(peerPending);}
        if(!endpoint.isEmpty() || federationIdentity!=null && !contacts().isEmpty()){
            if(httpPending!=null && !httpPending.isDone())paths.add(httpPending);
            else if(!periodic || System.nanoTime()>=httpRetryAfter){httpPending=CompletableFuture.supplyAsync(()->exchangeHttpAndContacts(epoch),worker);paths.add(httpPending);}
        }
        if(paths.isEmpty()){state=endpoint.isEmpty()?"NOT_CONFIGURED":"UNAVAILABLE";return CompletableFuture.completedFuture(false);}
        CompletableFuture<Boolean> result=new CompletableFuture<>();AtomicBoolean succeeded=new AtomicBoolean();java.util.concurrent.atomic.AtomicInteger remaining=new java.util.concurrent.atomic.AtomicInteger(paths.size());long started=System.nanoTime();
        for(CompletableFuture<Boolean> path:paths)path.whenComplete((ok,error)->{
            if(Boolean.TRUE.equals(ok) && succeeded.compareAndSet(false,true))result.complete(true);
            if(remaining.decrementAndGet()==0 && !succeeded.get()){
                synchronized(this){if(current(epoch) && lastSuccess<started){state="UNAVAILABLE";detail="Не удалось обновить каталог объявлений; свежие сохранённые объявления ещё могут отображаться";}}
                result.complete(false);
            }
        });return result;
    }
    synchronized void refreshIfDue(){if(!closed && !paused && (lastAttempt==0 || System.nanoTime()-lastAttempt>=TimeUnit.SECONDS.toNanos(5)))refresh(true);}
    synchronized DesktopBackend.DiscoveryStatus status(){return new DesktopBackend.DiscoveryStatus(state,detail,presences().size());}
    List<DiscoveryPresence> presences(){return cache.presences(System.currentTimeMillis()).stream().filter(p->!p.fingerprint.equals(fingerprint)).toList();}
    private synchronized boolean current(long epoch){return !closed && !paused && generation==epoch && !Thread.currentThread().isInterrupted();}
    private void refreshOwn()throws IOException {
        Publisher ownPublisher;ContactPublisher ownContact;synchronized(this){ownPublisher=publisher;ownContact=contactPublisher;}
        DiscoveryPresence presence=ownPublisher.current();FederationContact contact=ownContact.current();long now=System.currentTimeMillis();
        synchronized(this){
            removeCancelledSignals();
            if(presence!=null){if(!fingerprint.equals(presence.fingerprint))throw new IOException("Discovery publication identity");cache.accept(presence,now);}
            if(contact!=null){if(!fingerprint.equals(contact.fingerprint))throw new IOException("Discovery contact identity");cache.accept(contact,now);}
            outgoing.values().removeIf(signal->signal.expiresAt<=now);for(DiscoverySignal signal:outgoing.values())cache.accept(signal,now);
        }
    }
    private void received(long epoch){Runnable notified;synchronized(this){if(!current(epoch))return;lastSuccess=System.nanoTime();state="ONLINE";detail="";notified=signalListener;}if(notified!=null)notified.run();}
    private void saveContacts()throws IOException {if(contactFile!=null)contactFile.save(contacts());}
    private boolean exchangePeers(long epoch){
        Node currentNode;Supplier<List<Invitation>> source;synchronized(this){currentNode=peerNode;source=peers;}
        List<Invitation> invitations;try{invitations=new ArrayList<>(source.get());}catch(RuntimeException unavailable){return false;}
        if(currentNode==null)return false;int reached=0;
        for(Invitation invitation:invitations.subList(0,Math.min(3,invitations.size()))){
            if(!current(epoch))break;
            try{
                refreshOwn();boolean legacy=legacyPeers.getOrDefault(invitation.fingerprint,0L)>System.nanoTime();byte[] response;
                try{response=currentNode.exchangeDiscovery(invitation,legacy?cache.exportFrame(System.currentTimeMillis()):cache.exportFrameWithContacts(System.currentTimeMillis()));}
                catch(IOException unavailable){synchronized(this){if(legacy || !current(epoch) || peerNode!=currentNode)throw unavailable;}legacyPeers.put(invitation.fingerprint,System.nanoTime()+TimeUnit.MINUTES.toNanos(1));response=currentNode.exchangeDiscovery(invitation,cache.exportFrame(System.currentTimeMillis()));}
                synchronized(this){if(!current(epoch) || peerNode!=currentNode)return false;cache.ingestFrame(response,System.currentTimeMillis());removeCancelledSignals();saveContacts();}
                ++reached;received(epoch);
            }catch(IOException|RuntimeException unavailable){/* Independent authenticated paths can still answer. */}
        }
        synchronized(this){if(current(epoch) && peerNode==currentNode)livePeerExchanges=reached;}return reached>0;
    }
    private boolean exchangeHttpAndContacts(long epoch){
        boolean reached=!endpoint.isEmpty() && exchange(epoch,false);
        synchronized(this){if(!current(epoch))return false;if(reached){httpFailures=0;httpRetryAfter=0;}else{httpFailures=Math.min(6,httpFailures+1);httpRetryAfter=System.nanoTime()+TimeUnit.SECONDS.toNanos(Math.min(60,1L<<httpFailures));}}
        if(reached){
            long now=System.nanoTime();if(now-lastUpgradeProbe>=TimeUnit.MINUTES.toNanos(1)){lastUpgradeProbe=now;exchange(epoch,true);}
            return true;
        }
        TlsIdentity self;BiFunction<FederationContact,Long,InetSocketAddress> resolver;synchronized(this){self=federationIdentity;resolver=contactResolver;}if(self==null)return false;
        List<FederationContact> candidates=new ArrayList<>(contacts());candidates.removeIf(contact->contact.fingerprint.equals(fingerprint));
        if(!candidates.isEmpty())Collections.rotate(candidates,-Math.floorMod(contactCursor,candidates.size()));
        Map<String,FederationContact> byFingerprint=new LinkedHashMap<>();for(FederationContact contact:candidates)byFingerprint.put(contact.fingerprint,contact);
        List<String> ordered=contactFailover.order(new ArrayList<>(byFingerprint.keySet()),System.currentTimeMillis());
        long absoluteDeadline=System.currentTimeMillis()+Math.min(8000,deadlineMillis);int attempted=0;
        for(String pin:ordered){
            if(!current(epoch) || System.currentTimeMillis()>=absoluteDeadline || ++attempted>2)break;
            ++contactCursor;FederationContact contact=byFingerprint.get(pin);Socket socket=new Socket();ScheduledFuture<?> deadline=null;
            try(socket){
                synchronized(this){if(!current(epoch))return false;activeFederation=socket;}
                deadline=watchdog.schedule(()->{try{socket.close();}catch(IOException ignored){}},Math.max(1,absoluteDeadline-System.currentTimeMillis()),TimeUnit.MILLISECONDS);
                // The shared bounded resolver validates EVERY DNS answer and never grants LAN access from gossip.
                InetSocketAddress address=resolver.apply(contact,Math.min(absoluteDeadline,System.currentTimeMillis()+1000));
                long remaining=absoluteDeadline-System.currentTimeMillis();if(!current(epoch) || remaining<=0)throw new SocketTimeoutException("Federation deadline");
                socket.connect(address,(int)Math.min(1000,remaining));refreshOwn();DiscoveryStore own=new DiscoveryStore();long wall=System.currentTimeMillis();
                for(DiscoveryPresence presence:cache.presences(wall))if(presence.fingerprint.equals(fingerprint))own.accept(presence,wall);
                for(DiscoverySignal signal:cache.signals(wall))if(signal.senderFingerprint.equals(fingerprint))own.accept(signal,wall);
                FederationExchange.Result response=FederationExchange.exchange(self,socket,contact,own.exportFrame(wall));
                synchronized(this){if(!current(epoch) || System.currentTimeMillis()>=absoluteDeadline)return false;cache.ingestFrame(response.discoveryFrame,System.currentTimeMillis());removeCancelledSignals();for(FederationContact learned:response.contacts)cache.accept(learned,System.currentTimeMillis());saveContacts();contactFailover.succeeded(pin);}
                received(epoch);return true;
            }catch(IOException|RuntimeException unavailable){synchronized(this){if(current(epoch))contactFailover.failed(pin,System.currentTimeMillis());}}
            finally{if(deadline!=null)deadline.cancel(false);synchronized(this){if(activeFederation==socket)activeFederation=null;}}
        }
        return false;
    }
    private boolean exchange(long epoch,boolean withContacts){
        HttpURLConnection connection=null;ScheduledFuture<?> deadline=null;AtomicBoolean timedOut=new AtomicBoolean();long started=System.nanoTime();
        try{
            if(!current(epoch))throw new InterruptedIOException("Discovery cancelled");
            Publisher currentPublisher;synchronized(this){currentPublisher=publisher;}
            DiscoveryStore upload=new DiscoveryStore();DiscoveryPresence own=currentPublisher.current();
            if(own!=null){if(!fingerprint.equals(own.fingerprint))throw new IOException("Discovery publication identity");upload.accept(own,System.currentTimeMillis());}
            synchronized(this){long now=System.currentTimeMillis();outgoing.values().removeIf(signal->signal.expiresAt<=now);for(DiscoverySignal signal:outgoing.values())upload.accept(signal,now);}
            refreshOwn();if(withContacts)for(FederationContact contact:contacts())upload.accept(contact,System.currentTimeMillis());
            byte[] request=withContacts?upload.exportFrameWithContacts(System.currentTimeMillis()):upload.exportFrame(System.currentTimeMillis());
            connection=connections.open(new URL(endpoint));if(!(connection instanceof HttpsURLConnection) || !"https".equalsIgnoreCase(connection.getURL().getProtocol()))throw new IOException("HTTPS required");
            synchronized(this){if(!current(epoch))throw new InterruptedIOException("Discovery cancelled");activeRequest=connection;}
            HttpURLConnection active=connection;deadline=watchdog.schedule(()->{timedOut.set(true);active.disconnect();},deadlineMillis,TimeUnit.MILLISECONDS);
            connection.setConnectTimeout((int)Math.min(5000,deadlineMillis));connection.setReadTimeout((int)Math.min(5000,deadlineMillis));connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/octet-stream");connection.setFixedLengthStreamingMode(request.length);
            try(OutputStream output=connection.getOutputStream()){output.write(request);}
            if(connection.getResponseCode()!=200 || connection.getContentLengthLong()>DiscoveryStore.MAX_FRAME)throw new IOException("Discovery response unavailable");
            byte[] response;
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] chunk=new byte[4096];int count;
                while((count=input.read(chunk))!=-1){
                    if(!current(epoch) || timedOut.get() || System.nanoTime()-started>=TimeUnit.MILLISECONDS.toNanos(deadlineMillis))throw new InterruptedIOException("Discovery deadline");
                    if(bytes.size()+count>DiscoveryStore.MAX_FRAME)throw new IOException("Discovery frame too large");bytes.write(chunk,0,count);
                }
                response=bytes.toByteArray();
            }
            Runnable notified;
            synchronized(this){
                if(!current(epoch) || timedOut.get() || System.nanoTime()-started>=TimeUnit.MILLISECONDS.toNanos(deadlineMillis))throw new InterruptedIOException("Discovery cancelled");
                cache.ingestFrame(response,System.currentTimeMillis());removeCancelledSignals();saveContacts();lastSuccess=System.nanoTime();state="ONLINE";detail="";
                notified=signalListener;
            }
            if(notified!=null)notified.run();
            return true;
        }catch(IOException|RuntimeException failure){
            return false;
        }finally{if(deadline!=null)deadline.cancel(false);synchronized(this){if(activeRequest==connection)activeRequest=null;}if(connection!=null)connection.disconnect();}
    }
    synchronized void resume(){if(!closed)paused=false;}
    void pause(){HttpURLConnection active;Socket federation;synchronized(this){++generation;paused=true;active=activeRequest;federation=activeFederation;if(!closed){state="NOT_STARTED";detail="";}}if(active!=null)active.disconnect();if(federation!=null)try{federation.close();}catch(IOException ignored){}}
    @Override public void close(){synchronized(this){closed=true;state="NOT_STARTED";detail="";outgoing.clear();cancelledSignals.clear();signalListener=null;detachPeerExchange();}pause();worker.shutdownNow();peerWorker.shutdownNow();watchdog.shutdownNow();cache.clear();}
}
