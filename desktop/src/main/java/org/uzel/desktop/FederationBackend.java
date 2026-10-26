package org.uzel.desktop;

import org.uzel.core.*;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** Independent directory, reciprocal exit and Windows routing lifecycles. Call network operations on a worker. */
public final class FederationBackend implements DesktopBackend {
    interface ApplicationRouting extends AutoCloseable {
        WindowsAppRouter.Availability availability();
        WindowsAppRouter.Snapshot snapshot();
        void activate(List<Path> applications,DesktopTrafficService.ProxyEndpoint endpoint)throws IOException;
        default void activate(DesktopRoutingMode mode,List<Path> applications,DesktopTrafficService.ProxyEndpoint endpoint)throws IOException {activate(applications,endpoint);}
        void deactivate()throws IOException;
        void close();
    }
    @FunctionalInterface interface PairingFactory {DesktopPairingManager create(Node node,TlsIdentity identity,DiscoveryIdentity discoveryIdentity,DesktopDiscoveryClient discovery,DesktopPairingManager.Listener listener);}
    private static ApplicationRouting windowsRouting(Path root){
        WindowsAppRouter router=new WindowsAppRouter(root);
        return new ApplicationRouting(){
            public WindowsAppRouter.Availability availability(){return router.availability();}
            public WindowsAppRouter.Snapshot snapshot(){return router.snapshot();}
            public void activate(List<Path> apps,DesktopTrafficService.ProxyEndpoint proxy)throws IOException{router.activate(apps,proxy);}
            public void activate(DesktopRoutingMode mode,List<Path> apps,DesktopTrafficService.ProxyEndpoint proxy)throws IOException{router.activate(mode,apps,proxy);}
            public void deactivate()throws IOException{router.deactivate();}
            public void close(){router.close();}
        };
    }
    private record Peer(Invitation invitation,PeerRoute route,long expiresAt,String country,long sequence){}
    private final Path root;
    private final TlsIdentity identity;
    private final DesktopTrafficService traffic;
    private final ApplicationRouting router;
    private final DesktopDiscoveryClient discovery;
    private final PairingFactory pairingFactory;
    private final int trafficPort;
    private final ReentrantLock operations=new ReentrantLock();
    private final Object cancellationGate=new Object(),publicationGate=new Object();
    private final ScheduledExecutorService maintenance;
    private FederationNode node;
    private DesktopConfig configuration;
    private Snapshot stopped=new Snapshot(false,0,0,0,false,"");
    private final Map<String,Peer> manualPeers=new LinkedHashMap<>(),discoveredPeers=new LinkedHashMap<>();
    private List<Path> applications=List.of();
    private DesktopRoutingMode routingMode=DesktopRoutingMode.SELECTED_APPLICATIONS;
    private DiscoveryIdentity discoveryIdentity;
    private volatile DesktopPairingManager pairing;
    private CompletableFuture<Invitation> pendingPairing;
    private long directoryGeneration,sharingGeneration,connectionGeneration,establishedConnection;
    private volatile long lastPublication,sharingOwner,connectionOwner;
    private boolean closed;
    private String advertisedHost="",backendError="",connectionTarget="";

    public FederationBackend(Path root,TlsIdentity identity){this(root,identity,new DesktopTrafficService(identity),39281,windowsRouting(root),DesktopDiscoveryClient.configured(root,identity.fingerprint));}
    FederationBackend(Path root,TlsIdentity identity,DesktopDiscoveryClient discovery){this(root,identity,new DesktopTrafficService(identity),39281,windowsRouting(root),discovery);}
    FederationBackend(Path root,TlsIdentity identity,DesktopTrafficService traffic,int trafficPort){this(root,identity,traffic,trafficPort,windowsRouting(root));}
    FederationBackend(Path root,TlsIdentity identity,DesktopTrafficService traffic,int trafficPort,ApplicationRouting router){this(root,identity,traffic,trafficPort,router,DesktopDiscoveryClient.disabled(identity.fingerprint));}
    FederationBackend(Path root,TlsIdentity identity,DesktopTrafficService traffic,int trafficPort,ApplicationRouting router,DesktopDiscoveryClient discovery){this(root,identity,traffic,trafficPort,router,discovery,DesktopPairingManager::new);}
    FederationBackend(Path root,TlsIdentity identity,DesktopTrafficService traffic,int trafficPort,ApplicationRouting router,DesktopDiscoveryClient discovery,PairingFactory pairingFactory){
        this.root=Objects.requireNonNull(root);this.identity=Objects.requireNonNull(identity);this.traffic=Objects.requireNonNull(traffic);this.router=Objects.requireNonNull(router);
        this.discovery=Objects.requireNonNull(discovery);this.discovery.setPublisher(this::ownPresence);
        this.discovery.setFederationIdentity(identity);this.discovery.setContactPublisher(this::ownDiscoveryContact);
        try{this.discovery.setContactFile(root.resolve("discovery-contacts.bin"));}catch(IOException invalid){backendError="Сохранённые резервные контакты недоступны";}
        this.pairingFactory=Objects.requireNonNull(pairingFactory);
        if(trafficPort<0 || trafficPort>65535)throw new IllegalArgumentException("Traffic port");this.trafficPort=trafficPort;
        try{applications=DesktopApplications.load(root);}catch(IOException invalid){backendError="Выберите приложения заново: сохранённый файл недоступен";}
        try{routingMode=DesktopRoutingMode.load(root);}catch(IOException invalid){backendError="Выберите режим подключения заново";}
        maintenance=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"uzelio-desktop-sharing");thread.setDaemon(true);return thread;});
        maintenance.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);
    }
    public void start(DesktopConfig config)throws Exception {
        final long epoch;
        synchronized(this){if(closed)throw new IOException("Backend closed");if(node!=null)return;configuration=config;epoch=++directoryGeneration;}
        FederationNode candidate=new FederationNode(identity,root,InetAddress.getByName("0.0.0.0"),config.port(),sharingHost(config.advertisedHost()),config.allowPrivate());
        try{
            candidate.start();synchronized(this){if(closed || epoch!=directoryGeneration)throw new InterruptedIOException("Directory start cancelled");node=candidate;candidate=null;}
            publishOwn();
            synchronized(cancellationGate){
                synchronized(this){if(closed || epoch!=directoryGeneration || node==null)return;}
                discovery.resume();
            }
            discovery.refresh();
        }finally{if(candidate!=null)candidate.close();}
    }
    public void stop(){
        stopSharing();final FederationNode previous;
        synchronized(cancellationGate){
            synchronized(this){++directoryGeneration;previous=node;node=null;discoveredPeers.clear();}
            discovery.pause();
        }
        if(previous!=null){FederationNode.Snapshot state=previous.snapshot();String error="";try{previous.close();}catch(IOException e){error="Не удалось сохранить каталог";}
            synchronized(this){if(node==null)stopped=new Snapshot(false,state.contacts.size(),0,0,state.capacityLimited,error);}}
    }
    private synchronized FederationNode requireDirectory()throws IOException {if(node==null)throw new IOException("Сначала запустите сеть на главной");return node;}
    public void addContact(String code)throws Exception {requireDirectory().addContact(code);}
    public String ownCode()throws Exception {return requireDirectory().ownContact().encode();}
    public Snapshot snapshot(){
        FederationNode current;Snapshot previous;String error;synchronized(this){current=node;previous=stopped;error=backendError;}
        if(current==null)return error.isEmpty()?previous:new Snapshot(false,previous.knownContacts(),0,0,previous.capacityLimited(),error);
        FederationNode.Snapshot state=current.snapshot();return new Snapshot(state.running,state.contacts.size(),devices().size(),state.connectedNeighbors,state.capacityLimited,error.isEmpty()?state.lastError:error);
    }
    public List<DeviceDirectoryView.Device> devices(){
        FederationNode current;synchronized(this){current=node;}
        FederationNode.Snapshot state=current==null?null:current.snapshot();if(state!=null)importRoutes(current,state.routes);
        long now=System.currentTimeMillis();Map<String,DeviceDirectoryView.Device> result=new TreeMap<>();
        synchronized(this){
            discoveredPeers.values().removeIf(peer->peer.expiresAt()<=now);
            manualPeers.forEach((fingerprint,peer)->result.put(fingerprint,new DeviceDirectoryView.Device(fingerprint,peer.country(),0,0,peer.expiresAt())));
            discoveredPeers.forEach((fingerprint,peer)->result.put(fingerprint,new DeviceDirectoryView.Device(fingerprint,peer.country(),0,0,peer.expiresAt())));
        }
        if(state!=null)for(DeviceDirectoryView.Device device:DeviceDirectoryView.fromPresences(state.presences,now))result.put(device.fingerprint(),device);
        for(DeviceDirectoryView.Device device:DeviceDirectoryView.fromPresences(discovery.presences(),now))result.put(device.fingerprint(),device);
        result.remove(identity.fingerprint);return List.copyOf(result.values());
    }
    private void importRoutes(FederationNode expected,List<PeerDirectory.Record> routes){
        final Map<String,Peer> known;
        synchronized(this){if(node!=expected)return;known=new HashMap<>(discoveredPeers);}
        long now=System.currentTimeMillis();Map<String,Peer> verified=new LinkedHashMap<>();
        for(PeerDirectory.Record input:routes)try{
            Peer previous=known.get(input.fingerprint);if(previous!=null && previous.sequence()>=input.sequence)continue;
            PeerDirectory.Record record=PeerDirectory.Record.parse(input.encode());
            if(!record.fingerprint.equals(identity.fingerprint) && record.issuedAt<=now+30_000 && record.expiresAt>now)
                verified.put(record.fingerprint,new Peer(null,record.route,record.expiresAt,record.country,record.sequence));
        }catch(IOException ignored){}
        synchronized(this){
            if(node!=expected)return;discoveredPeers.values().removeIf(peer->peer.expiresAt()<=now);
            verified.forEach((fingerprint,peer)->{Peer previous=discoveredPeers.get(fingerprint);
                if((previous==null || peer.sequence()>previous.sequence()) && (previous!=null || discoveredPeers.size()<256))discoveredPeers.put(fingerprint,peer);});
        }
    }
    public SearchResult refreshDevices()throws Exception {
        FederationNode current=requireDirectory();CompletableFuture<Boolean> bootstrap=discovery.refresh();int visited=0;
        try{FederationNode.LookupResult result=current.lookup();importRoutes(current,result.routes);visited=result.visitedServers;}
        catch(IOException unavailable){/* The independent signed HTTP catalog can still answer. */}
        boolean reached;
        try{reached=bootstrap.get(21,TimeUnit.SECONDS);}catch(TimeoutException timeout){reached=false;}catch(ExecutionException unavailable){reached=false;}
        return new SearchResult(visited+(reached?1:0),true);
    }
    public DiscoveryStatus discoveryStatus(){return discovery.status();}
    public void startSharing()throws Exception {
        final long epoch;synchronized(this){if(closed)throw new IOException("Backend closed");epoch=++sharingGeneration;}
        operations.lock();
        try{
            synchronized(this){if(closed || sharingGeneration!=epoch)throw new InterruptedIOException("Sharing start cancelled");}
            if(traffic.snapshot().sharing())return;
            SharingRatePreference preference=SharingRatePreference.load(root);traffic.setShareRatePreference(preference);
            DesktopConfig config;synchronized(this){config=configuration;}
            if(config==null)config=DesktopConfig.load(root);String host=sharingHost(config.advertisedHost());
            synchronized(cancellationGate){synchronized(this){if(closed || sharingGeneration!=epoch)throw new InterruptedIOException("Sharing start cancelled");}sharingOwner=epoch;}
            try{
                traffic.startSharing(trafficPort);
                synchronized(this){if(closed || sharingGeneration!=epoch)throw new InterruptedIOException("Sharing start cancelled");advertisedHost=host;backendError="";}
                Node current=traffic.node();current.setAdvertisedRoute(current.publicRoute(host));
                final DiscoveryIdentity signalingIdentity;
                synchronized(publicationGate){if(discoveryIdentity==null)discoveryIdentity=new DiscoveryIdentity(identity);signalingIdentity=discoveryIdentity;}
                synchronized(cancellationGate){
                    synchronized(this){if(closed || sharingGeneration!=epoch)throw new InterruptedIOException("Sharing start cancelled");}
                    pairing=pairingFactory.create(current,identity,signalingIdentity,discovery,fingerprint->pairedTransportLost(current,epoch,fingerprint));
                    discovery.attachPeerExchange(current,this::discoveryPeers);
                }
                publishOwn();discovery.refresh();
            }catch(Exception failure){stopOwnedSharing(epoch);throw failure;}
        }finally{operations.unlock();}
    }
    private static String sharingHost(String configured)throws SocketException {
        if(!configured.equalsIgnoreCase("localhost") && !configured.equals("0.0.0.0") && !configured.equals("::"))return configured;
        Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
        while(interfaces!=null && interfaces.hasMoreElements()){
            NetworkInterface network=interfaces.nextElement();if(!network.isUp() || network.isLoopback())continue;
            Enumeration<InetAddress> addresses=network.getInetAddresses();while(addresses.hasMoreElements()){
                InetAddress address=addresses.nextElement();if(address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress())return address.getHostAddress();
            }
        }
        return "127.0.0.1";
    }
    public void stopSharing(){long owner;synchronized(this){++sharingGeneration;owner=sharingOwner;advertisedHost="";}disconnect();stopOwnedSharing(owner);}
    private void stopOwnedSharing(long owner){synchronized(cancellationGate){if(sharingOwner!=owner && sharingOwner!=0)return;sharingOwner=0;discovery.detachPeerExchange();DesktopPairingManager previous=pairing;pairing=null;if(previous!=null)previous.close();traffic.stopSharing();}}
    private List<Invitation> discoveryPeers(){
        LinkedHashMap<String,Invitation> invitations=new LinkedHashMap<>();DesktopPairingManager manager=pairing;
        if(manager!=null)for(Invitation invitation:manager.invitations())invitations.put(invitation.fingerprint,invitation);
        synchronized(this){for(Peer peer:manualPeers.values())if(peer.invitation()!=null)invitations.putIfAbsent(peer.invitation().fingerprint,peer.invitation());}
        return invitations.values().stream().limit(3).toList();
    }
    int backupPeerCount(){DesktopPairingManager manager=pairing;return manager==null?0:manager.backupCount();}
    public void measureUpload()throws Exception {
        operations.lock();try{disconnect();traffic.remeasure();publishOwn();}finally{operations.unlock();}
    }
    public void setSharingRate(SharingRatePreference choice)throws Exception {
        operations.lock();try{
            SharingRatePreference previous=new SharingRatePreference(traffic.snapshot().requestedShareRateLimit());traffic.setShareRatePreference(choice);
            try{choice.save(root);}catch(IOException failure){traffic.setShareRatePreference(previous);throw failure;}
        }finally{operations.unlock();}
    }
    public String sharingCode()throws Exception {String host;synchronized(this){host=advertisedHost;}if(!traffic.snapshot().sharing() || host.isEmpty())throw new IOException("Сначала включите раздачу");return traffic.invitation(host).encode();}
    public void addTrafficPeer(String code)throws Exception {
        Invitation invitation=null;PeerRoute route=null;
        if(code!=null && code.trim().startsWith("uzel-public://"))route=PeerRoute.parse(code.trim());else invitation=Invitation.parse(code==null?null:code.trim());
        String fingerprint=invitation==null?route.fingerprint:invitation.fingerprint;
        if(fingerprint.equals(identity.fingerprint))throw new IOException("Это код этого компьютера");
        synchronized(this){if(manualPeers.size()>=256 && !manualPeers.containsKey(fingerprint))throw new IOException("Список устройств заполнен");manualPeers.put(fingerprint,new Peer(invitation,route,Long.MAX_VALUE,"",0));}
    }
    private synchronized Peer peer(String fingerprint){Peer manual=manualPeers.get(fingerprint);if(manual!=null)return manual;Peer found=discoveredPeers.get(fingerprint);return found!=null && found.expiresAt()>System.currentTimeMillis()?found:null;}
    public boolean hasTrafficRoute(String fingerprint){DesktopPairingManager manager=pairing;return peer(fingerprint)!=null || manager!=null && manager.invitation(fingerprint)!=null;}
    public boolean canConnect(String fingerprint){
        boolean selected;synchronized(this){selected=(routingMode==DesktopRoutingMode.ALL_TRAFFIC || !applications.isEmpty()) && !closed;}
        DesktopTrafficService.Snapshot state=traffic.snapshot();return selected && (hasTrafficRoute(fingerprint) || pairing!=null && discovery.presence(fingerprint)!=null) && state.sharing() && !state.measuring() && state.measuredUpload()>0 && router.availability().installed();
    }
    public void connect(String fingerprint)throws Exception {
        final long epoch;
        synchronized(cancellationGate){
            synchronized(this){if(closed)throw new IOException("Backend closed");if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Connection cancelled");epoch=++connectionGeneration;}
            cancelOwnedConnection(connectionOwner);
        }
        operations.lock();
        try{
            requireConnectionEpoch(epoch);
            if(!canConnect(fingerprint))throw new IOException("Нужны раздача, измерение, выбранные приложения и установленный Windows-модуль");
            Peer target=peer(fingerprint);DesktopPairingManager automatic=pairing;
            if(target==null && (automatic==null || discovery.presence(fingerprint)==null))throw new IOException("Маршрут или объявление устройства истекло");
            DesktopRoutingMode mode=routingMode();List<Path> selected=mode==DesktopRoutingMode.ALL_TRAFFIC?List.of():DesktopApplications.canonicalSelection(selectedApplications());
            try{
                synchronized(cancellationGate){requireConnectionEpoch(epoch);connectionOwner=epoch;synchronized(this){establishedConnection=0;connectionTarget=fingerprint;}traffic.disconnect();router.deactivate();}
                requireConnectionEpoch(epoch);Runnable lost=()->peerLost(epoch);
                if(target!=null){if(target.invitation()!=null)traffic.connect(target.invitation(),lost);else traffic.connect(target.route(),lost);}
                else{
                    Invitation paired;CompletableFuture<Invitation> waiting;
                    synchronized(cancellationGate){requireConnectionEpoch(epoch);waiting=automatic.connect(discovery.presence(fingerprint));pendingPairing=waiting;}
                    try{paired=waiting.get(185,TimeUnit.SECONDS);}
                    catch(CancellationException cancelled){throw new InterruptedIOException("Connection cancelled");}
                    catch(ExecutionException unavailable){throw new IOException("Не удалось автоматически соединиться с устройством",unavailable.getCause());}
                    catch(TimeoutException timeout){waiting.cancel(true);throw new IOException("Автосопряжение превысило время ожидания");}
                    finally{if(!waiting.isDone())waiting.cancel(true);synchronized(cancellationGate){if(pendingPairing==waiting)pendingPairing=null;}}
                    requireConnectionEpoch(epoch);traffic.connect(paired,lost);
                }
                requireConnectionEpoch(epoch);DesktopTrafficService.ProxyEndpoint endpoint=traffic.localProxy();if(endpoint==null)throw new IOException("Peer disconnected");
                router.activate(mode,selected,endpoint);requireConnectionEpoch(epoch);
                if(!router.snapshot().active() || !traffic.snapshot().connected())throw new IOException("Windows routing or peer unavailable");
                synchronized(this){if(closed || connectionGeneration!=epoch)throw new InterruptedIOException("Connection cancelled");establishedConnection=epoch;backendError="";}
            }catch(Exception failure){cancelOwnedConnection(epoch);throw failure;}
        }finally{operations.unlock();}
    }
    private synchronized void requireConnectionEpoch(long epoch)throws IOException {if(closed || connectionGeneration!=epoch || Thread.currentThread().isInterrupted())throw new InterruptedIOException("Connection cancelled");}
    private void peerLost(long epoch){cancelOwnedConnection(epoch);}
    private void pairedTransportLost(Node expectedNode,long expectedSharingOwner,String fingerprint){
        synchronized(cancellationGate){
            if(sharingOwner!=expectedSharingOwner)return;
            try{if(traffic.node()!=expectedNode)return;}catch(IOException stopped){return;}
            long owner;synchronized(this){owner=connectionTarget.equals(fingerprint)?connectionOwner:0;}
            if(owner!=0)cancelOwnedConnection(owner);
        }
    }
    public String pairingState(){DesktopPairingManager manager=pairing;return manager==null?"OFF":manager.state();}
    /** A live paired transport's chat stream; without one, asks the catalog pairing to connect. */
    public Socket openChatStream(String fingerprint)throws IOException {
        DesktopPairingManager manager=pairing;
        if(manager==null)return null;
        Socket live=manager.openChatStream(fingerprint);
        if(live!=null)return live;
        manager.requestChatSession(fingerprint);
        return null;
    }
    private void cancelOwnedConnection(long owner){
        synchronized(cancellationGate){
            if(connectionOwner!=owner && connectionOwner!=0)return;connectionOwner=0;
            synchronized(this){if(connectionGeneration==owner)++connectionGeneration;if(establishedConnection==owner)establishedConnection=0;connectionTarget="";}
            CompletableFuture<Invitation> waiting=pendingPairing;pendingPairing=null;if(waiting!=null)waiting.cancel(true);
            traffic.disconnect();try{router.deactivate();}catch(IOException failure){synchronized(this){backendError="Не удалось остановить Windows-модуль";}}
        }
    }
    public void disconnect(){long owner;synchronized(this){++connectionGeneration;establishedConnection=0;owner=connectionOwner;}cancelOwnedConnection(owner);}
    public DesktopTrafficService.Snapshot traffic(){
        DesktopTrafficService.Snapshot state=traffic.snapshot();long established;synchronized(this){established=establishedConnection;}
        boolean connected=established!=0 && established==connectionOwner && state.connected() && router.snapshot().active();
        return new DesktopTrafficService.Snapshot(state.sharing(),connected,state.measuring(),state.sharedBytes(),state.receivedBytes(),state.measuredUpload(),state.shareRateLimit(),state.requestedShareRateLimit(),state.clients(),state.country(),connectionOwner!=0 && !connected?"CONNECTING":state.status());
    }
    public synchronized List<Path> selectedApplications(){return applications;}
    public synchronized DesktopRoutingMode routingMode(){return routingMode;}
    public void setRoutingMode(DesktopRoutingMode mode)throws Exception {
        Objects.requireNonNull(mode);if(routingMode()==mode)return;disconnect();operations.lock();
        try{disconnect();mode.save(root);synchronized(this){routingMode=mode;backendError="";}}finally{operations.unlock();}
    }
    public void selectApplications(List<Path> selected)throws Exception {
        List<Path> canonical=DesktopApplications.canonicalSelection(selected);disconnect();operations.lock();
        try{disconnect();DesktopApplications.save(root,canonical);synchronized(this){applications=canonical;backendError="";}}finally{operations.unlock();}
    }
    public WindowsAppRouter.Availability routingAvailability(){return router.availability();}
    public WindowsAppRouter.Snapshot routingState(){return router.snapshot();}

    private void tick(){
        try{
            long established;synchronized(this){if(closed)return;established=establishedConnection;}
            if(established!=0 && (!router.snapshot().active() || !traffic.snapshot().connected()))cancelOwnedConnection(established);
            boolean running;synchronized(this){running=node!=null;}if(running)discovery.refreshIfDue();
            DesktopPairingManager manager=pairing;if(manager!=null && connectionOwner==0)manager.maintainBackups();
            if(System.currentTimeMillis()-lastPublication>=30_000)publishOwn();
        }catch(Exception ignored){}
    }
    private void publishOwn()throws IOException {
        synchronized(publicationGate){
            final FederationNode directory;final String host;final long epoch;
            synchronized(this){directory=node;host=advertisedHost;epoch=sharingGeneration;if(closed)return;}
            DesktopTrafficService.Snapshot state=traffic.snapshot();if(directory==null || host.isEmpty() || !state.sharing() || state.measuredUpload()<=0)return;
            if(discoveryIdentity==null)discoveryIdentity=new DiscoveryIdentity(identity);
            long now=System.currentTimeMillis();DiscoveryPresence own=discoveryIdentity.presence(state.country(),state.clients(),state.measuredUpload(),now);
            Node current=traffic.node();PeerDirectory.Record route=current.publish(current.publicRoute(host));
            synchronized(this){if(node!=directory || sharingGeneration!=epoch || closed)return;}
            // Only freshly signed OWN data enters publication stores. Lookup results remain observations.
            directory.discoveryStore().accept(own,now);directory.acceptRoute(route);lastPublication=now;
        }
    }
    private DiscoveryPresence ownPresence()throws IOException {
        synchronized(publicationGate){
            synchronized(this){if(closed)return null;}
            DesktopTrafficService.Snapshot state=traffic.snapshot();if(!state.sharing() || state.measuring() || state.measuredUpload()<=0)return null;
            if(discoveryIdentity==null)discoveryIdentity=new DiscoveryIdentity(identity);
            return discoveryIdentity.presence(state.country(),state.clients(),state.measuredUpload(),System.currentTimeMillis());
        }
    }
    private FederationContact ownDiscoveryContact()throws IOException {
        FederationNode current;synchronized(this){if(closed)return null;current=node;}
        return current==null?null:current.ownContact();
    }
    public void close(){synchronized(this){if(closed)return;closed=true;}maintenance.shutdownNow();stop();discovery.close();traffic.close();router.close();}
}
