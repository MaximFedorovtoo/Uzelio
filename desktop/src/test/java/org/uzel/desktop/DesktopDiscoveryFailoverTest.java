package org.uzel.desktop;

import org.junit.Test;
import org.uzel.core.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.Assert.*;

public class DesktopDiscoveryFailoverTest {
    @Test(timeout=5000) public void pauseDuringFailedPeerDoesNotStartLegacyRetry()throws Exception {
        TlsIdentity a=identity(),b=identity();CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger requests=new AtomicInteger();
        try(Node na=new Node(a,new NetworkAccess(),ip->true);Node nb=new Node(b,new NetworkAccess(),ip->true);DesktopDiscoveryClient client=client(a,()->null,new AtomicInteger())){
            na.start(0);nb.start(0);na.setMeasuredUpload(3_000_000);nb.setMeasuredUpload(3_000_000);nb.registerPeerTransport(a.fingerprint,()->new Socket("127.0.0.1",na.port()));
            nb.setDiscoveryExchange(frame->{requests.incrementAndGet();reading.countDown();try{release.await();}catch(InterruptedException stopped){Thread.currentThread().interrupt();}throw new IOException("peer unavailable");});attach(client,na,()->List.of(nb.invitation("127.0.0.1")));
            Future<Boolean> result=client.refresh();assertTrue(reading.await(1,TimeUnit.SECONDS));client.pause();release.countDown();assertFalse(result.get(2,TimeUnit.SECONDS));assertEquals("Pause must prevent a second physical peer request",1,requests.get());
        }finally{release.countDown();}
    }
    @Test(timeout=10000) public void failedContactRotationReachesThirdHintAndBacksOffFailures()throws Exception {
        TlsIdentity own=identity(),first=identity(),second=identity(),third=identity();Path file=Files.createTempDirectory("desktop-fallback-order-").resolve("contacts.bin");
        List<FederationContact> contacts=List.of(FederationContact.create(first,"192.168.0.1",7777,System.currentTimeMillis()),FederationContact.create(second,"192.168.0.2",7777,System.currentTimeMillis()),FederationContact.create(third,"192.168.0.3",7777,System.currentTimeMillis()));
        new DesktopDiscoveryContacts(file).save(contacts);List<String> asked=new CopyOnWriteArrayList<>();
        try(DesktopDiscoveryClient client=client(own,()->null,new AtomicInteger())){
            client.setContactFile(file);client.setFederationIdentity(own);resolver(client,(contact,deadline)->{asked.add(contact.fingerprint);throw new UncheckedIOException(new IOException("unavailable"));});
            assertFalse(client.refresh().get(2,TimeUnit.SECONDS));assertEquals(2,asked.size());assertFalse(client.refresh().get(2,TimeUnit.SECONDS));
            assertEquals("Every signed hint must get a turn even when the first two are private or down",3,new HashSet<>(asked).size());assertEquals(3,asked.size());
        }
    }
    @Test(timeout=10000) public void pauseClosesActiveFederationSocket()throws Exception {
        TlsIdentity own=identity(),remote=identity();Path file=Files.createTempDirectory("desktop-fallback-close-").resolve("contacts.bin");
        new DesktopDiscoveryContacts(file).save(List.of(FederationContact.create(remote,"93.184.216.34",7777,System.currentTimeMillis())));
        ExecutorService accepted=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,2,InetAddress.getLoopbackAddress());DesktopDiscoveryClient client=new DesktopDiscoveryClient(own.fingerprint,"https://offline.example/v1/exchange",url->{throw new IOException("offline");},()->null,1000)){
            CountDownLatch reading=new CountDownLatch(1);Future<?> receive=accepted.submit(()->{try(Socket socket=server.accept()){reading.countDown();socket.setSoTimeout(2000);while(socket.getInputStream().read()!=-1){}}catch(IOException failure){throw new UncheckedIOException(failure);}});
            client.setContactFile(file);client.setFederationIdentity(own);resolver(client,(contact,deadline)->new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()));
            Future<Boolean> refresh=client.refresh();assertTrue(reading.await(1,TimeUnit.SECONDS));client.pause();assertFalse(refresh.get(1,TimeUnit.SECONDS));receive.get(1,TimeUnit.SECONDS);
        }finally{accepted.shutdownNow();}
    }
    @Test(timeout=5000) public void federationHandshakeObeysAbsoluteDeadline()throws Exception {
        TlsIdentity own=identity(),remote=identity();Path file=Files.createTempDirectory("desktop-fallback-deadline-").resolve("contacts.bin");new DesktopDiscoveryContacts(file).save(List.of(FederationContact.create(remote,"93.184.216.34",7777,System.currentTimeMillis())));
        ExecutorService accepted=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,2,InetAddress.getLoopbackAddress());DesktopDiscoveryClient client=new DesktopDiscoveryClient(own.fingerprint,"https://offline.example/v1/exchange",url->{throw new IOException("offline");},()->null,150)){
            Future<?> receive=accepted.submit(()->{try(Socket socket=server.accept()){socket.setSoTimeout(2000);while(socket.getInputStream().read()!=-1){}}catch(IOException failure){throw new UncheckedIOException(failure);}});
            client.setContactFile(file);client.setFederationIdentity(own);resolver(client,(contact,deadline)->new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()));
            assertFalse("Handshake must not outlive the whole fallback deadline",client.refresh().get(1,TimeUnit.SECONDS));receive.get(1,TimeUnit.SECONDS);
        }finally{accepted.shutdownNow();}
    }
    private static void resolver(DesktopDiscoveryClient client,java.util.function.BiFunction<FederationContact,Long,InetSocketAddress> resolver)throws Exception {
        client.setContactResolver(resolver);
    }
    @Test(timeout=5000) public void peerSupplierNeverRunsUnderDiscoveryMonitor()throws Exception {
        TlsIdentity a=identity();ExecutorService observer=Executors.newSingleThreadExecutor();AtomicBoolean blocked=new AtomicBoolean();
        try(Node node=new Node(a,new NetworkAccess(),ip->true);DesktopDiscoveryClient client=client(a,()->null,new AtomicInteger())){
            node.start(0);node.setMeasuredUpload(3_000_000);attach(client,node,()->{
                try{observer.submit(client::status).get(300,TimeUnit.MILLISECONDS);}catch(Exception locked){blocked.set(true);}return List.of();
            });
            client.refresh().get(2,TimeUnit.SECONDS);assertFalse("Calling backend supplier while holding discovery lock can deadlock start/stop",blocked.get());
        }finally{observer.shutdownNow();}
    }
    @Test(timeout=15000) public void peerLearnedSignedContactsSurviveRestartWithoutPresenceOrSdp()throws Exception {
        TlsIdentity a=identity(),b=identity();Path file=Files.createTempDirectory("desktop-backup-hints-").resolve("contacts.bin");
        FederationContact contact=FederationContact.create(b,"192.168.0.25",7777,System.currentTimeMillis());
        try(Node na=new Node(a,new NetworkAccess(),ip->true);Node nb=new Node(b,new NetworkAccess(),ip->true)){
            na.start(0);nb.start(0);na.setMeasuredUpload(3_000_000);nb.setMeasuredUpload(3_000_000);nb.registerPeerTransport(a.fingerprint,()->new Socket("127.0.0.1",na.port()));
            try(DesktopDiscoveryClient ca=client(a,()->null,new AtomicInteger());DesktopDiscoveryClient cb=client(b,()->new DiscoveryIdentity(b).presence("CA",0,3_000_000,System.currentTimeMillis()),new AtomicInteger())){
                ca.setContactFile(file);cb.setContactPublisher(()->contact);attach(ca,na,()->List.of(nb.invitation("127.0.0.1")));attach(cb,nb,List::of);
                assertTrue(ca.refresh().get(3,TimeUnit.SECONDS));assertEquals(b.fingerprint,ca.contacts().get(0).fingerprint);assertTrue(Files.exists(file));
            }
        }
        try(DesktopDiscoveryClient restored=client(a,()->null,new AtomicInteger())){
            restored.setContactFile(file);assertEquals(contact.encode(),restored.contacts().get(0).encode());assertTrue(restored.presences().isEmpty());assertTrue(restored.signals().isEmpty());
            assertThrows(IOException.class,()->restored.contacts().get(0).address(new InetAddress[]{InetAddress.getByName("192.168.0.25")},false));
        }
    }
    @Test(timeout=15000) public void failedPeersCannotReportCachedPresenceAsOnlineAndPauseStopsPeerWork()throws Exception {
        TlsIdentity a=identity(),b=identity();AtomicBoolean online=new AtomicBoolean(true);AtomicInteger reads=new AtomicInteger();
        try(Node na=new Node(a,new NetworkAccess(),ip->true);Node nb=new Node(b,new NetworkAccess(),ip->true)){
            na.start(0);nb.start(0);na.setMeasuredUpload(3_000_000);nb.setMeasuredUpload(3_000_000);nb.registerPeerTransport(a.fingerprint,()->new Socket("127.0.0.1",na.port()));
            try(DesktopDiscoveryClient ca=client(a,()->null,new AtomicInteger())){
                DiscoveryStore remote=new DiscoveryStore();remote.accept(new DiscoveryIdentity(b).presence("CA",0,3_000_000,System.currentTimeMillis()),System.currentTimeMillis());
                nb.setDiscoveryExchange(frame->{reads.incrementAndGet();if(!online.get())throw new IOException("peer down");return remote.exchange(frame,System.currentTimeMillis());});attach(ca,na,()->List.of(nb.invitation("127.0.0.1")));
                assertTrue(ca.refresh().get(3,TimeUnit.SECONDS));online.set(false);assertFalse(ca.refresh().get(3,TimeUnit.SECONDS));assertEquals("UNAVAILABLE",ca.status().state());assertNotNull(ca.presence(b.fingerprint));
                ca.pause();int previous=reads.get();assertFalse(ca.refresh().get(1,TimeUnit.SECONDS));assertEquals(previous,reads.get());
            }
        }
    }
    @Test(timeout=20000) public void livePeersCarryFreshPresenceAndEncryptedSignalWhileHttpsIsDown()throws Exception {
        TlsIdentity a=identity(),b=identity(),newcomer=identity();DiscoveryIdentity da=new DiscoveryIdentity(a),db=new DiscoveryIdentity(b),dc=new DiscoveryIdentity(newcomer);
        AtomicInteger failedHttp=new AtomicInteger();
        try(Node na=new Node(a,new NetworkAccess(),ip->true);Node nb=new Node(b,new NetworkAccess(),ip->true)){
            na.start(0);nb.start(0);na.setMeasuredUpload(3_000_000);nb.setMeasuredUpload(3_000_000);
            na.registerPeerTransport(b.fingerprint,()->new Socket("127.0.0.1",nb.port()));nb.registerPeerTransport(a.fingerprint,()->new Socket("127.0.0.1",na.port()));
            AtomicReference<DiscoveryPresence> published=new AtomicReference<>();
            try(DesktopDiscoveryClient ca=client(a,()->da.presence("CA",0,3_000_000,System.currentTimeMillis()),failedHttp);
                DesktopDiscoveryClient cb=client(b,()->db.presence("RU",0,3_000_000,System.currentTimeMillis()),failedHttp);
                DesktopDiscoveryClient fresh=client(newcomer,published::get,failedHttp)){
                attach(ca,na,()->List.of(nb.invitation("127.0.0.1")));attach(cb,nb,List::of);
                assertTrue(ca.refresh().get(3,TimeUnit.SECONDS));assertNotNull(ca.presence(b.fingerprint));
                assertNull(ca.presence(newcomer.fingerprint));
                // This identity and encrypted offer are created only AFTER the HTTPS path has failed.
                published.set(dc.presence("GB",0,4_000_000,System.currentTimeMillis()));
                Node nc=new Node(newcomer,new NetworkAccess(),ip->true);
                try(nc){nc.start(0);nc.setMeasuredUpload(4_000_000);nb.registerPeerTransport(newcomer.fingerprint,()->new Socket("127.0.0.1",nc.port()));attach(fresh,nc,()->List.of(nb.invitation("127.0.0.1")));assertTrue(fresh.refresh().get(3,TimeUnit.SECONDS));}
                assertTrue(ca.refresh().get(3,TimeUnit.SECONDS));assertNotNull(ca.presence(newcomer.fingerprint));
                String offer=PairingCode.offer(a,"fresh-offer-after-outage",na.invitation("127.0.0.1"),System.currentTimeMillis()).encode();
                DiscoverySignal signal=da.seal(ca.presence(b.fingerprint),offer,System.currentTimeMillis());
                ca.publishSignal(signal);assertTrue(ca.refresh().get(3,TimeUnit.SECONDS));
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(cb.signals().isEmpty() && System.nanoTime()<until){ca.refresh().get(2,TimeUnit.SECONDS);Thread.sleep(10);}
                assertEquals(offer,db.open(cb.signals().get(0),System.currentTimeMillis()));
                assertEquals("ONLINE",ca.status().state());assertTrue(failedHttp.get()>0);
            }
        }
    }
    @Test(timeout=15000) public void workingPeerRefreshDoesNotWaitForBlockedHttpsRequest()throws Exception {
        TlsIdentity a=identity(),b=identity();CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
        try(Node na=new Node(a,new NetworkAccess(),ip->true);Node nb=new Node(b,new NetworkAccess(),ip->true)){
            na.start(0);nb.start(0);na.setMeasuredUpload(3_000_000);nb.setMeasuredUpload(3_000_000);
            nb.registerPeerTransport(a.fingerprint,()->new Socket("127.0.0.1",na.port()));
            try(DesktopDiscoveryClient ca=new DesktopDiscoveryClient(a.fingerprint,"https://offline.example/v1/exchange",url->{blocked.countDown();try{release.await();}catch(InterruptedException stop){Thread.currentThread().interrupt();}throw new IOException("offline");},()->null,5000);
                DesktopDiscoveryClient cb=client(b,()->new DiscoveryIdentity(b).presence("CA",0,3_000_000,System.currentTimeMillis()),new AtomicInteger())){
                Future<Boolean> old=ca.refresh();assertTrue(blocked.await(1,TimeUnit.SECONDS));
                attach(ca,na,()->List.of(nb.invitation("127.0.0.1")));attach(cb,nb,List::of);
                assertTrue("Live peer must not queue behind bootstrap timeout",ca.refresh().get(2,TimeUnit.SECONDS));
                assertNotNull(ca.presence(b.fingerprint));
            }finally{release.countDown();}
        }
    }
    private static void attach(DesktopDiscoveryClient client,Node node,Supplier<List<Invitation>> peers)throws Exception {
        client.attachPeerExchange(node,peers);
    }
    private static DesktopDiscoveryClient client(TlsIdentity identity,DesktopDiscoveryClient.Publisher publisher,AtomicInteger requests){return new DesktopDiscoveryClient(identity.fingerprint,"https://offline.example/v1/exchange",url->{requests.incrementAndGet();throw new IOException("Synology offline");},publisher,1000);}
    private static TlsIdentity identity()throws Exception{return IdentityStore.load(Files.createTempDirectory("desktop-failover-"));}
}
