package org.uzel.app;

import android.content.*;
import android.net.*;
import org.json.*;
import org.uzel.core.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single process owner. UI never fabricates connection state. */
public final class NodeController {
    private static volatile NodeController instance;
    public static NodeController get(Context context){
        if(instance==null)synchronized(NodeController.class){if(instance==null)instance=new NodeController(context);}
        return instance;
    }
    public static final class Peer {
        public String id="",name="",host="",country="";
        public boolean reachable,paired,autoDiscovered,announced;
        public int clients,latencyMs=-1,freeSlots;
        public long upload;
        DiscoveryPresence presence;
        Invitation invite;
        PeerRoute route;
        long expiresAt,checkedAt;
        Peer copy(){Peer p=new Peer();p.id=id;p.name=name;p.host=host;p.country=country;long now=System.currentTimeMillis();p.reachable=reachable && now-checkedAt<120_000 && (expiresAt==0 || expiresAt>now);p.clients=clients;p.latencyMs=latencyMs;p.paired=paired;p.autoDiscovered=autoDiscovered;p.announced=announced&&presence!=null&&presence.expiresAt>now;p.freeSlots=freeSlots;p.upload=upload;return p;}
    }
    public static final class Snapshot {
        public boolean sharing,vpnConnected,connecting,busy,publicNetwork,bootstrapHosting;
        public String discoveryState="NOT_CONFIGURED",discoveryURL="";
        public int bootstrapPort,directoryContacts;
        public long completedDiscoveryRefresh;
        public String publicContact="",networkDetail="",pairingState="IDLE",pairingCode="";
        public String status="",country="",connectedCountry="",publicIp="",invitation="",advertisedHost="",errorDetail="";
        public long sharedBytes,receivedBytes,uploadBps,shareLimitBps,requestedShareRateBps,minimumShareRateBps;
        public int clients;
        public List<Peer> peers=new ArrayList<>();
    }
    private final Context context;
    private final SharedPreferences prefs;
    private final ConnectivityManager connectivity;
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final ExecutorService peerWork=Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshing=new AtomicBoolean();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentMap<String,Peer> peers=new ConcurrentHashMap<>();
    private final AtomicLong generation=new AtomicLong();
    private final AtomicLong vpnGeneration=new AtomicLong();
    private volatile Node node;
    private volatile Node.Session session;
    private volatile LocalSocks socks;
    private volatile UzelVpnService service;
    private volatile PhysicalNetwork physical;
    private volatile PairingManager pairing;
    private volatile AutoDiscoveryManager automatic;
    private volatile String bootstrapUrl="",pendingAutoId="";
    private volatile boolean bootstrapHosting;
    private volatile long requestedShareRateBps;
    private volatile PeerDiscovery discovery;
    private volatile Node.RelayLease relay;
    private volatile boolean publicNetwork;
    private volatile String networkDetail="";
    private int catalogCursor,probeCursor;
    private volatile boolean busy,vpnConnected,sharingRequested;
    private volatile String connectedCountry="";
    private volatile String status="OFF",country="",publicIp="",advertisedHost;
    private volatile long lastMeasurement,lastRefresh,storedShared,storedReceived;

    private NodeController(Context context){
        this.context=context.getApplicationContext();
        prefs=this.context.getSharedPreferences("node",Context.MODE_PRIVATE);
        connectivity=this.context.getSystemService(ConnectivityManager.class);
        advertisedHost=prefs.getString("host","");
        bootstrapUrl=prefs.getString("bootstrapUrl",context.getString(R.string.bootstrap_default_url));bootstrapHosting=prefs.getBoolean("bootstrapHosting",false);
        requestedShareRateBps=Math.max(0,prefs.getLong("shareRateBps",0));
        publicNetwork=prefs.getBoolean("publicNetwork",false);
        storedShared=prefs.getLong("shared",0);storedReceived=prefs.getLong("received",0);
        try{
            JSONArray values=new JSONArray(prefs.getString("peers","[]"));
            for(int i=0;i<Math.min(values.length(),64);i++)addPeer(Invitation.parse(values.getString(i)));
        }catch(Exception ignored){}
        try{
            JSONArray routes=new JSONArray(prefs.getString("publicPeers","[]"));
            for(int i=0;i<Math.min(routes.length(),64);i++)addPublicPeer(PeerRoute.parse(routes.getString(i)));
        }catch(Exception ignored){}
        NetworkRequest request=new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
        connectivity.registerNetworkCallback(request,new ConnectivityManager.NetworkCallback(){
            @Override public void onLost(Network network){
                synchronized(NodeController.this) {
                    PhysicalNetwork current=physical;
                    if(current!=null && current.network.equals(network)) {
                        UzelVpnService currentService=service;
                        stopResources();
                        status="UNREACHABLE";
                        if(sharingRequested && currentService!=null)startInternal(currentService);
                    }
                }
            }
            @Override public void onAvailable(Network network){
                synchronized(NodeController.this) {
                    UzelVpnService current=service;
                    if(sharingRequested && current!=null && node==null && !busy)startInternal(current);
                }
            }
        });
        timer.scheduleWithFixedDelay(()->{
            try {
                Node current=node;
                if(current!=null) {
                    synchronized(this){
                        if(vpnConnected && (session==null || !session.isLive() || service==null || !service.tunnelRunning())){
                            disconnect();status="UNREACHABLE";
                        }
                    }
                    if(!busy && System.currentTimeMillis()-lastRefresh>30_000)refreshPeers();
                    if(!busy && lastMeasurement>0 && System.currentTimeMillis()-lastMeasurement>15*60_000)measure();
                    persistCounters();
                }
            }catch(Exception ignored){}
        },3,3,TimeUnit.SECONDS);
    }
    public Snapshot snapshot(){
        Snapshot s=new Snapshot();Node n=node;
        s.sharing=n!=null && n.isSharing();s.vpnConnected=vpnConnected;s.busy=busy;s.status=status;
        s.connecting=!pendingAutoId.isEmpty()||(busy&&"CONNECTING".equals(status));
        s.country=country;s.publicIp=publicIp;s.advertisedHost=advertisedHost;
        s.connectedCountry=vpnConnected?connectedCountry:"";
        s.publicNetwork=publicNetwork;s.networkDetail=networkDetail;
        AutoDiscoveryManager autoOwner=automatic;
        s.discoveryURL=bootstrapUrl;s.discoveryState=autoOwner==null?"NOT_CONFIGURED":autoOwner.state();
        s.completedDiscoveryRefresh=autoOwner==null?0:autoOwner.completedRefresh();
        s.directoryContacts=autoOwner==null?context.getSharedPreferences("directory_contacts",Context.MODE_PRIVATE).getStringSet("codes",Collections.emptySet()).size():autoOwner.directoryContactCount();
        s.requestedShareRateBps=requestedShareRateBps;s.shareLimitBps=requestedShareRateBps;
        s.bootstrapHosting=autoOwner!=null&&autoOwner.hosting();s.bootstrapPort=autoOwner==null?0:autoOwner.port();
        s.busy=s.busy||!pendingAutoId.isEmpty();
        PairingManager pairingOwner=pairing;
        if(pairingOwner!=null){s.pairingState=pairingOwner.state();s.pairingCode=pairingOwner.code();s.errorDetail=pairingOwner.diagnostic();}
        s.sharedBytes=storedShared+(n==null?0:n.sharedBytes.get());
        s.receivedBytes=storedReceived+(n==null?0:n.receivedBytes.get());
        if(n!=null){
            s.clients=n.clientCount();s.uploadBps=n.measuredUpload();s.shareLimitBps=n.limit();
            s.minimumShareRateBps=n.minimumShareRateLimit();
            try{s.invitation=n.invitation(advertisedHost.isEmpty()?localHost():advertisedHost).encode();}
            catch(Exception ignored){}
            if(publicNetwork)try{s.publicContact=ownRoute(n).encode();}catch(Exception ignored){}
        }
        for(Peer p:peers.values())s.peers.add(p.copy());
        s.peers.sort(Comparator.comparing(p->p.id));
        return s;
    }
    public synchronized void startSharing(){
        if(node!=null || busy)return;
        sharingRequested=true;prefs.edit().putBoolean("autoShare",true).apply();
        status="STARTING";
        context.startForegroundService(new Intent(context,UzelVpnService.class).setAction("START"));
    }
    synchronized void startInternal(UzelVpnService owner){
        if(!sharingRequested){owner.stopSelf();return;}
        if(service!=null && service!=owner)return;
        service=owner;
        if(node!=null || busy)return;
        busy=true;status="STARTING";long epoch=generation.get();
        work.execute(()->{
            try{
                Network network=findNetwork();
                if(network==null)throw new IOException("UNREACHABLE");
                PhysicalNetwork access=new PhysicalNetwork(network);
                String secret=prefs.getString("secret","");
                // Persist before publishing an invitation. This runs on the worker, not the UI thread.
                if(secret.isEmpty()){
                    secret=TlsIdentity.randomSecret();
                    if(!prefs.edit().putString("secret",secret).commit())throw new IOException("Cannot store identity");
                }
                Node created=new Node(DeviceIdentity.load(),access,PublicAddress::allowed,secret);
                synchronized(this){
                    if(epoch!=generation.get() || !sharingRequested || service!=owner){created.close();return;}
                    created.setShareRateLimit(requestedShareRateBps);created.start(39281);node=created;physical=access;
                    pairing=new PairingManager(context,access,created,new PairingManager.Listener(){
                        public void available(Invitation invitation){
                            synchronized(NodeController.this){
                                if(epoch!=generation.get()||node!=created)return;
                                Peer previous=peers.get(invitation.fingerprint);
                                addPeer(invitation);Peer peer=peers.get(invitation.fingerprint);peer.paired=true;
                                if(previous!=null){peer.autoDiscovered=previous.autoDiscovered;peer.presence=previous.presence;peer.announced=previous.announced;}
                                if(pendingAutoId.equals(invitation.fingerprint))verifyAutomaticPeer(created,peer,epoch);
                            }
                            refreshPeers();
                        }
                        public void unavailable(String fingerprint){
                            synchronized(NodeController.this){
                                if(epoch!=generation.get()||node!=created)return;
                                Peer peer=peers.get(fingerprint);if(peer!=null&&peer.paired)peers.remove(fingerprint,peer);
                            }
                        }
                    });
                    automatic=new AutoDiscoveryManager(context,created,access,pairing,bootstrapUrl,bootstrapHosting,this::pairedInvitations,new AutoDiscoveryManager.Listener(){
                        public void catalog(List<DiscoveryPresence> values){updateAutomaticCatalog(created,epoch,values);}
                        public void failed(String fingerprint){synchronized(NodeController.this){if(epoch==generation.get()&&node==created&&pendingAutoId.equals(fingerprint)){pendingAutoId="";status="UNREACHABLE";}}}
                    });
                    created.setPublicMode(publicNetwork);
                    PeerDiscovery d=new PeerDiscovery(context);discovery=d;
                    d.start(created.invitation("127.0.0.1").fingerprint,39281,this::discovered);
                }
                fetchCountry(access,created,epoch);
                calibrate(created,access,epoch);
                if(epoch==generation.get()){status="SHARING";refreshPeers();}
            }catch(Exception e){if(epoch==generation.get())status=errorCode(e);}
            finally{if(epoch==generation.get())busy=false;}
        });
    }
    public synchronized void stopSharing(){prefs.edit().putBoolean("autoShare",false).apply();
        sharingRequested=false;
        UzelVpnService old=service;
        stopResources();status="OFF";
        service=null;
        if(old!=null){old.stopForeground(true);old.stopSelf();}
    }
    synchronized void serviceDestroyed(UzelVpnService owner){
        if(service==owner){sharingRequested=false;stopResources();service=null;status="OFF";}
    }
    private synchronized void stopResources(){
        generation.incrementAndGet();busy=false;pendingAutoId="";
        AutoDiscoveryManager oldAutomatic=automatic;automatic=null;if(oldAutomatic!=null)oldAutomatic.close();
        disconnect();
        PairingManager oldPairing=pairing;pairing=null;if(oldPairing!=null)oldPairing.close();
        peers.entrySet().removeIf(entry->entry.getValue().paired);
        Node old=node;node=null;
        // Revoke forwarding immediately; TLS close can write close_notify and must run off the UI.
        if(old!=null)old.stopAdmission();
        Node.RelayLease oldRelay=relay;relay=null;
        PeerDiscovery d=discovery;discovery=null;
        long shared=old==null?0:old.sharedBytes.get(),received=old==null?0:old.receivedBytes.get();
        storedShared+=shared;storedReceived+=received;
        persistCounters();
        // Same queue as start/connect: close the old listener before its replacement binds.
        work.execute(()->{
            try{if(oldRelay!=null)oldRelay.close();}
            finally{
                try{if(old!=null)old.close();}
                finally{
                    if(d!=null)d.close();
                    synchronized(this){
                        if(old!=null){storedShared+=old.sharedBytes.get()-shared;storedReceived+=old.receivedBytes.get()-received;}
                        persistCounters();
                    }
                }
            }
        });
        physical=null;lastMeasurement=0;country="";publicIp="";
        for(Peer p:peers.values())p.reachable=false;
    }
    public synchronized void disconnect(){
        vpnGeneration.incrementAndGet();
        String autoTarget=pendingAutoId;pendingAutoId="";
        AutoDiscoveryManager manager=automatic;if(manager!=null&&!autoTarget.isEmpty())manager.cancel(autoTarget);
        busy=false;
        vpnConnected=false;
        Node.Session old=session;session=null;if(old!=null)old.invalidate();
        LocalSocks proxy=socks;socks=null;
        // Stop only our tunnel before returning to UI or allowing a replacement session.
        UzelVpnService owner=service;if(owner!=null)owner.stopTunnel();
        if(old!=null || proxy!=null)work.execute(()->{
            try{if(old!=null)old.close();}
            finally{if(proxy!=null)proxy.close();}
        });
        if(node!=null && node.isSharing())status="SHARING";
    }
    public synchronized void connect(String peerId){
        Node current=node;Peer peer=peers.get(peerId);
        if(current==null || !current.isSharing()){status="NEED_SHARING";return;}
        if(busy)return;
        if(peer==null){status="UNREACHABLE";return;}
        if(peer.autoDiscovered&&!peer.paired&&peer.presence!=null){
            AutoDiscoveryManager manager=automatic;
            if(manager==null||!pendingAutoId.isEmpty()||peer.presence.expiresAt<=System.currentTimeMillis()){status="UNREACHABLE";return;}
            if(!AppSelection.vpnReady(context)){status="SELECT_APPS";return;}
            if(VpnService.prepare(context)!=null){status="ERROR";return;}
            disconnect();
            pendingAutoId=peer.id;status="CONNECTING";manager.connect(peer.presence);return;
        }
        if(peer.invite==null && peer.route==null){status="UNREACHABLE";return;}
        if(peer.expiresAt>0 && peer.expiresAt<=System.currentTimeMillis()){status="UNREACHABLE";return;}
        if(!AppSelection.vpnReady(context)){status="SELECT_APPS";return;}
        if(VpnService.prepare(context)!=null){status="ERROR";return;}
        disconnect();
        busy=true;status="CONNECTING";long epoch=generation.get();
        long vpnEpoch=vpnGeneration.get();
        work.execute(()->{
            Node.Session joined=null;LocalSocks bridge=null;
            try{
                if(current.measuredUpload()<=0)calibrate(current,physical,epoch);
                if(peer.expiresAt>0 && peer.expiresAt<=System.currentTimeMillis())throw new IOException("Expired route");
                if(vpnEpoch!=vpnGeneration.get())throw new IOException("Cancelled");
                Runnable lost=()->{
                    synchronized(NodeController.this) {
                        if(vpnEpoch==vpnGeneration.get() && epoch==generation.get()){
                            disconnect();status="UNREACHABLE";
                        }
                    }
                };
                joined=peer.route!=null ? current.connect(peer.route,lost) : current.connect(peer.invite,lost);
                bridge=new LocalSocks(current,joined);bridge.start();
                synchronized(this){
                    if(epoch!=generation.get() || vpnEpoch!=vpnGeneration.get() || service==null)
                        throw new IOException("Cancelled");
                    session=joined;socks=bridge;
                    service.startTunnel(bridge,physical.network);
                    if(!joined.isLive())throw new IOException("UNREACHABLE");
                    connectedCountry=peer.country;vpnConnected=true;status="CONNECTED";
                }
            }catch(Exception | LinkageError e){
                if(joined!=null)joined.close();if(bridge!=null)bridge.close();
                synchronized(this){
                    if(epoch==generation.get() && vpnEpoch==vpnGeneration.get()){disconnect();status=errorCode(e);}
                }
            }finally{if(epoch==generation.get() && vpnEpoch==vpnGeneration.get())busy=false;}
        });
    }
    public synchronized void measure(){
        Node current=node;PhysicalNetwork access=physical;
        if(current==null || access==null){status="NEED_SHARING";return;}
        if(busy)return;
        long epoch=generation.get();
        disconnect();busy=true;
        work.execute(()->{
            try{
                if(epoch!=generation.get() || node!=current)return;
                current.setMeasuredUpload(0);
                calibrate(current,access,epoch);if(epoch==generation.get())status="SHARING";}
            catch(Exception e){if(epoch==generation.get())status="NEED_MEASUREMENT";}
            finally{if(epoch==generation.get())busy=false;}
        });
    }
    private void calibrate(Node current,PhysicalNetwork access,long epoch)throws IOException {
        if(access==null)throw new IOException("UNREACHABLE");
        synchronized(this){
            if(epoch!=generation.get() || node!=current)throw new InterruptedIOException();
            status="MEASURING";
        }
        try {
            long speed=InternetMeasurement.upload(access,()->epoch!=generation.get());
            synchronized(this){
                if(epoch!=generation.get() || node!=current)throw new InterruptedIOException();
                current.setMeasuredUpload(speed);lastMeasurement=System.currentTimeMillis();
            }
        }catch(IOException e){
            current.setMeasuredUpload(0);throw new IOException("NEED_MEASUREMENT",e);
        }
    }
    public void addInvitation(String value){
        if(!value.startsWith("uzel://")){
            PeerRoute route=PeerRoute.parse(value);
            if(peers.size()>=64 && !peers.containsKey(route.fingerprint))throw new IllegalArgumentException("Too many nodes");
            addPublicPeer(route);savePeers();refreshPeers();return;
        }
        Invitation invite=Invitation.parse(value);
        if(peers.size()>=64 && !peers.containsKey(invite.fingerprint))throw new IllegalArgumentException("Too many nodes");
        addPeer(invite);savePeers();refreshPeers();
    }
    public synchronized void addDirectoryContact(String code)throws IOException {
        AutoDiscoveryManager manager=automatic;
        if(manager!=null)manager.addDirectoryContact(code);else new DirectoryContacts(context).add(code);
    }
    public boolean privateDirectoriesAllowed(){return context.getSharedPreferences("directory_contacts",Context.MODE_PRIVATE).getBoolean("allowPrivate",false);}
    public synchronized void setPrivateDirectoriesAllowed(boolean allowed){
        AutoDiscoveryManager manager=automatic;
        if(manager!=null)manager.allowPrivateDirectories(allowed);else new DirectoryContacts(context).setAllowPrivate(allowed);
    }
    public synchronized void setShareRateLimit(long bytesPerSecond){
        if(bytesPerSecond<0)throw new IllegalArgumentException("Rate");
        Node current=node;if(current!=null)current.setShareRateLimit(bytesPerSecond);
        requestedShareRateBps=bytesPerSecond;prefs.edit().putLong("shareRateBps",bytesPerSecond).apply();
    }
    public synchronized void setBootstrapUrl(String value){
        bootstrapUrl=AutoDiscoveryManager.validateUrl(value);prefs.edit().putString("bootstrapUrl",bootstrapUrl).apply();
        AutoDiscoveryManager manager=automatic;if(manager!=null)manager.configure(bootstrapUrl,bootstrapHosting);
    }
    public synchronized void setBootstrapHosting(boolean enabled){
        bootstrapHosting=enabled;prefs.edit().putBoolean("bootstrapHosting",enabled).apply();
        AutoDiscoveryManager manager=automatic;if(manager!=null)manager.configure(bootstrapUrl,enabled);
    }
    private List<Invitation> pairedInvitations(){
        List<Invitation> values=new ArrayList<>();for(Peer peer:peers.values())if(peer.paired&&peer.invite!=null)values.add(peer.invite);return values;
    }
    private synchronized void updateAutomaticCatalog(Node expected,long epoch,List<DiscoveryPresence> values){
        if(epoch!=generation.get()||node!=expected)return;
        long now=System.currentTimeMillis();String own=expected.invitation("127.0.0.1").fingerprint;
        peers.entrySet().removeIf(e->e.getValue().autoDiscovered&&!e.getValue().paired&&e.getValue().expiresAt<=now);
        for(DiscoveryPresence presence:values){
            if(presence.fingerprint.equals(own)||presence.expiresAt<=now)continue;
            Peer peer=peers.get(presence.fingerprint);
            if(peer==null){if(peers.size()>=64)continue;peer=new Peer();peer.id=presence.fingerprint;peer.name="Uzelio · "+peer.id.substring(0,8);peers.put(peer.id,peer);}
            peer.autoDiscovered=true;peer.announced=true;peer.presence=presence;
            if(!peer.paired){peer.country=presence.country;peer.clients=presence.clients;peer.upload=presence.upload;peer.freeSlots=Math.max(0,3-presence.clients);peer.expiresAt=presence.expiresAt;}
        }
    }
    private void verifyAutomaticPeer(Node expected,Peer peer,long epoch){
        String selected=peer.id;long selectedVpnEpoch=vpnGeneration.get();
        peerWork.execute(()->{
            try{
                Node.Info info=expected.inspect(peer.invite);
                synchronized(this){
                    if(epoch!=generation.get()||selectedVpnEpoch!=vpnGeneration.get()||node!=expected||!pendingAutoId.equals(selected))return;
                    peer.country=info.country;peer.clients=info.clients;peer.upload=info.upload;peer.freeSlots=Math.max(0,3-info.clients);
                    peer.latencyMs=info.latencyMs;peer.reachable=info.sharing&&info.upload>0;peer.checkedAt=System.currentTimeMillis();
                    pendingAutoId="";
                    if(!peer.reachable){status="UNREACHABLE";return;}
                }
                work.execute(()->{synchronized(this){if(epoch==generation.get()&&vpnGeneration.get()==selectedVpnEpoch&&node==expected)connect(selected);}});
            }catch(IOException unavailable){synchronized(this){if(epoch==generation.get()&&selectedVpnEpoch==vpnGeneration.get()&&pendingAutoId.equals(selected)){pendingAutoId="";status="UNREACHABLE";}}}
        });
    }
    public void createPairing(){PairingManager owner=pairing;if(owner!=null&&!busy)owner.offer();}
    /** A live paired chat stream; without one, asks the catalog discovery to pair this device. */
    public synchronized Socket openChatStream(String fingerprint)throws IOException {
        PairingManager owner=pairing;
        if(owner==null)return null;
        Socket live=owner.openChatStream(fingerprint);
        if(live!=null)return live;
        AutoDiscoveryManager manager=automatic;
        Peer peer=peers.get(fingerprint);
        if(manager!=null&&peer!=null&&peer.presence!=null&&!manager.isPairing()
                &&peer.presence.expiresAt>System.currentTimeMillis()){
            manager.connect(peer.presence);
        }
        return null;
    }
    public void acceptPairing(String value){PairingManager owner=pairing;if(owner!=null&&!busy)owner.accept(value);}
    public void cancelPairing(){PairingManager owner=pairing;if(owner!=null)owner.cancel();}
    private void addPeer(Invitation invite){
        Peer p=new Peer();p.id=invite.fingerprint;p.name="Uzelio · "+p.id.substring(0,8);
        p.host=invite.host;p.invite=invite;peers.put(p.id,p);
    }
    public void setAdvertisedHost(String host){
        if(host==null)throw new IllegalArgumentException("host");
        if(!host.isEmpty())new Invitation(host,39281,
                "abababababababababababababababababababababababababababababababab",
                "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd");
        advertisedHost=host;prefs.edit().putString("host",host).apply();
    }
    public synchronized void setPublicNetwork(boolean enabled){
        if(publicNetwork==enabled)return;
        publicNetwork=enabled;prefs.edit().putBoolean("publicNetwork",enabled).apply();
        Node current=node;
        // Serialize both toggles with teardown; rapid off/on must retain its ordering.
        if(current!=null){
            if(!enabled)current.stopPublicAdmission();
            work.execute(()->{
                if(!enabled)current.setPublicMode(false);
                else synchronized(this){
                    if(node==current && publicNetwork)current.setPublicMode(true);
                }
            });
        }
        if(!enabled){
            Node.RelayLease old=relay;relay=null;if(old!=null)work.execute(old::close);
            disconnect();networkDetail="PUBLIC_OFF";
            for(Peer peer:peers.values())if(peer.route!=null)peer.reachable=false;
        }else{networkDetail="DISCOVERING";refreshPeers();}
    }
    private void addPublicPeer(PeerRoute route){
        Peer p=new Peer();p.id=route.fingerprint;p.name="Uzelio · "+p.id.substring(0,8);
        p.host=route.host;p.route=route;peers.put(p.id,p);
    }
    private PeerRoute ownRoute(Node current)throws IOException{
        Node.RelayLease active=relay;
        if(active!=null && active.isLive())return active.route();
        String host=advertisedHost;
        if(host.isEmpty()){
            PhysicalNetwork access=physical;
            LinkProperties properties=access==null?null:connectivity.getLinkProperties(access.network);
            if(properties!=null)for(LinkAddress address:properties.getLinkAddresses())
                if(address.getAddress() instanceof Inet6Address && PublicAddress.allowed(address.getAddress())){
                    host=address.getAddress().getHostAddress();break;
                }
        }
        if(host.isEmpty())host=publicIp.isEmpty()?localHost():publicIp;
        return current.publicRoute(host);
    }
    private void refreshPublicNetwork(Node current,long epoch){
        try{
            if(!publicNetwork || epoch!=generation.get())return;
            PeerRoute own=ownRoute(current);
            current.setAdvertisedRoute(own);current.publish(own);
            boolean contacted=false;
            List<Peer> contacts=new ArrayList<>(peers.values());
            contacts.sort(Comparator.comparing(p->p.id));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            for(int attempt=0;attempt<Math.min(4,contacts.size()) && System.nanoTime()<deadline;attempt++){
                Peer peer=contacts.get(Math.floorMod(catalogCursor++,contacts.size()));
                if(epoch!=generation.get() || !publicNetwork)return;
                if(peer.route==null || peer.id.equals(own.fingerprint))continue;
                try{
                    current.exchangeDirectory(peer.route);contacted=true;
                    Node.RelayLease active=relay;
                    if((active==null || !active.isLive()) && !peer.route.isRelay()){
                        Node.RelayLease created=current.registerRelay(peer.route);
                        synchronized(this){
                            if(epoch!=generation.get() || !publicNetwork){created.close();return;}
                            Node.RelayLease old=relay;relay=created;if(old!=null)old.close();
                            current.setAdvertisedRoute(created.route());current.publish(created.route());
                        }
                    }
                }catch(IOException ignored){synchronized(this){if(epoch==generation.get() && node==current && publicNetwork)peer.reachable=false;}}
            }
            synchronized(this){
            if(epoch!=generation.get() || node!=current || !publicNetwork)return;
            long now=System.currentTimeMillis();
            peers.entrySet().removeIf(entry->entry.getValue().expiresAt>0 && entry.getValue().expiresAt<=now);
            for(PeerDirectory.Record record:current.directory().records()){
                if(record.fingerprint.equals(own.fingerprint))continue;
                Peer existing=peers.get(record.fingerprint);
                if(existing!=null && existing.expiresAt==0)continue;
                if(existing==null && peers.size()>=64)break;
                addPublicPeer(record.route);
                Peer added=peers.get(record.fingerprint);added.country=record.country;added.expiresAt=record.expiresAt;
            }
            if(epoch==generation.get() && publicNetwork){
                Node.RelayLease active=relay;
                networkDetail=active!=null && active.isLive()?"RELAY_CONNECTED":contacted?"DIRECT_UNVERIFIED":"BOOTSTRAP_REQUIRED";
            }
            }
        }catch(IOException e){synchronized(this){if(epoch==generation.get() && node==current && publicNetwork)networkDetail="BOOTSTRAP_REQUIRED";}}
    }
    private void savePeers(){
        JSONArray values=new JSONArray();
        JSONArray routes=new JSONArray();
        for(Peer p:peers.values())if(p.invite!=null&&!p.paired)values.put(p.invite.encode());
        for(Peer p:peers.values())if(p.route!=null && p.expiresAt==0 && routes.length()<64)routes.put(p.route.encode());
        prefs.edit().putString("peers",values.toString()).putString("publicPeers",routes.toString()).apply();
    }
    private void discovered(String pin,String host){
        if(host.contains("%"))return;
        Peer known=peers.get(pin);
        if(known!=null&&known.paired)return;
        if(known==null){
            if(peers.size()>=64)return;
            Peer p=new Peer();p.id=pin;p.host=host;p.name="Uzelio · "+pin.substring(0,8);
            peers.putIfAbsent(pin,p);return;
        }
        // Only try the new address for an already-pinned invitation; TLS still checks its key.
        Invitation old=known.invite;
        if(old!=null){known.invite=new Invitation(host,old.port,old.fingerprint,old.secret);known.host=host;}
    }
    public long refreshDiscovery(){AutoDiscoveryManager owner=automatic;return owner==null?-1:owner.requestRefresh();}
    public void refreshPeers(){
        lastRefresh=System.currentTimeMillis();Node current=node;
        if(current==null || !refreshing.compareAndSet(false,true))return;
        long epoch=generation.get();
        peerWork.execute(()->{
            try {
            if(publicNetwork)refreshPublicNetwork(current,epoch);
            List<Peer> candidates=new ArrayList<>(peers.values());
            candidates.sort(Comparator.comparing(p->p.id));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            for(int attempt=0;attempt<Math.min(6,candidates.size()) && System.nanoTime()<deadline;attempt++){
                Peer peer=candidates.get(Math.floorMod(probeCursor++,candidates.size()));
                if(epoch!=generation.get())return;
                if(peer.invite==null && peer.route==null)continue;
                try{
                    if(peer.route!=null && !publicNetwork){peer.reachable=false;continue;}
                    Node.Info info=peer.route!=null ? current.inspect(peer.route) : current.inspect(peer.invite);
                    synchronized(NodeController.this){
                    if(epoch!=generation.get() || node!=current || (peer.route!=null && !publicNetwork))return;
                    peer.country=info.country;peer.clients=info.clients;peer.latencyMs=info.latencyMs;peer.upload=info.upload;peer.freeSlots=Math.max(0,3-info.clients);
                    // A paired identity already owns a capacity slot on the remote node.
                    peer.reachable=info.sharing && info.upload>0 && (info.clients<3 || (peer.paired && info.clients==3));
                    peer.checkedAt=System.currentTimeMillis();
                    }
                }catch(IOException e){synchronized(NodeController.this){if(epoch==generation.get() && node==current){peer.reachable=false;peer.latencyMs=-1;}}}
            }
            } finally {refreshing.set(false);}
        });
    }
    private Network findNetwork(){
        Network active=connectivity.getActiveNetwork();
        if(isPhysical(active))return active;
        for(Network n:connectivity.getAllNetworks())if(isPhysical(n))return n;
        return null;
    }
    private boolean isPhysical(Network network){
        NetworkCapabilities caps=network==null?null:connectivity.getNetworkCapabilities(network);
        return caps!=null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
    }
    private String localHost()throws IOException {
        PhysicalNetwork access=physical;
        LinkProperties properties=access==null?null:connectivity.getLinkProperties(access.network);
        if(properties!=null){
            for(LinkAddress value:properties.getLinkAddresses())
                if(value.getAddress() instanceof Inet4Address && !value.getAddress().isLoopbackAddress())return value.getAddress().getHostAddress();
            for(LinkAddress value:properties.getLinkAddresses())
                if(!value.getAddress().isLinkLocalAddress() && !value.getAddress().isLoopbackAddress())return value.getAddress().getHostAddress();
        }
        throw new IOException("No local address");
    }
    private void fetchCountry(PhysicalNetwork access,Node current,long epoch){
        HttpURLConnection c=null;
        try{
            c=access.http("https://api.country.is/");
            if(c.getResponseCode()!=200)return;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){
                byte[] buffer=new byte[1024];int length;
                while((length=in.read(buffer))!=-1){if(bytes.size()+length>4096)throw new IOException();bytes.write(buffer,0,length);}
            }
            JSONObject data=new JSONObject(bytes.toString("UTF-8"));
            String found=data.optString("country",""),ip=data.optString("ip","");
            synchronized(this){
                if(epoch==generation.get() && found.matches("[A-Z]{2}") && ip.matches("[0-9a-fA-F:.]{3,45}")){
                    country=found;publicIp=ip;current.country=found;
                }
            }
        }catch(Exception ignored){/* Unknown is more honest than locale-based geolocation. */}
        finally{if(c!=null)c.disconnect();}
    }
    private synchronized void persistCounters(){
        Node current=node;
        prefs.edit().putLong("shared",storedShared+(current==null?0:current.sharedBytes.get()))
                .putLong("received",storedReceived+(current==null?0:current.receivedBytes.get())).apply();
    }
    private static String errorCode(Throwable error){
        String value=error.getMessage();
        if("CAPACITY".equals(value)||"NEED_SHARING".equals(value)||"NEED_MEASUREMENT".equals(value)||"SELECT_APPS".equals(value))return value;
        return "UNREACHABLE";
    }
}
