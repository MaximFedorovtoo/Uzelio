package org.uzel.desktop;

import org.junit.Test;
import org.uzel.core.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class DesktopPairingManagerTest {
    @Test(timeout=15000) public void explicitFutureCancellationRetiresDeferredGlareUntilNewSignal()throws Exception {assertCancelledGlareIsRetired(true);}
    @Test(timeout=15000) public void cancelPendingRetiresDeferredGlareUntilNewSignal()throws Exception {assertCancelledGlareIsRetired(false);}
    private static void assertCancelledGlareIsRetired(boolean cancelFuture)throws Exception {
        TlsIdentity a=identity(),b=identity();TlsIdentity lower=a.fingerprint.compareTo(b.fingerprint)<0?a:b,higher=lower==a?b:a;
        try(Fixture f=new Fixture(5000,lower,higher)){
            CompletableFuture<Invitation> outgoing=f.manager.connect(f.phonePresence);f.offer();FakeTransport old=f.fake.get();
            String code=PairingCode.offer(f.phoneIdentity,"deferred-glare",new Invitation("127.0.0.1",f.phone.port(),f.phoneIdentity.fingerprint,f.phoneToken),System.currentTimeMillis()).encode();
            DiscoverySignal signal=f.phoneDiscovery.seal(f.currentPcPresence(),code,System.currentTimeMillis());f.server.accept(signal,System.currentTimeMillis());f.client.refresh().get(2,TimeUnit.SECONDS);Thread.sleep(100);assertSame(old,f.fake.get());
            if(cancelFuture)assertTrue(outgoing.cancel(true));else f.manager.cancelPending(f.phoneIdentity.fingerprint);
            await(()->old.closed);f.client.refresh().get(2,TimeUnit.SECONDS);Thread.sleep(220);assertSame("Canceled deferred offer must not resurrect from timer or gossip",old,f.fake.get());
            f.server.accept(f.phoneDiscovery.seal(f.currentPcPresence(),code,System.currentTimeMillis()),System.currentTimeMillis());f.client.refresh().get(2,TimeUnit.SECONDS);await(()->f.fake.get()!=old);assertEquals(1,f.fake.get().offers.get());
        }
    }
    @Test(timeout=15000) public void freshOfferWhileOldPairIsActiveIsDeferredAndAcceptedOnceAfterClosed()throws Exception {
        try(Fixture f=new Fixture()){
            CompletableFuture<Invitation> initial=f.manager.connect(f.phonePresence);PairingCode first=f.offer();f.answer(first,f.phoneIdentity,f.phoneDiscovery,f.phonePresence);initial.get(2,TimeUnit.SECONDS);FakeTransport old=f.fake.get();
            String offer=PairingCode.offer(f.phoneIdentity,"fresh-after-remote-restart",new Invitation("127.0.0.1",f.phone.port(),f.phoneIdentity.fingerprint,f.phoneToken),System.currentTimeMillis()).encode();
            DiscoverySignal signal=f.phoneDiscovery.seal(f.currentPcPresence(),offer,System.currentTimeMillis());f.server.accept(signal,System.currentTimeMillis());f.client.refresh().get(2,TimeUnit.SECONDS);Thread.sleep(80);
            assertSame(old,f.fake.get());assertFalse("An incoming offer must not replace a still live transport",old.closed);
            // Factory publication precedes acceptOffer on the serial executor.
            old.listener.state("CLOSED");f.client.refresh().get(2,TimeUnit.SECONDS);await(()->f.fake.get()!=old&&f.fake.get().offers.get()==1);FakeTransport replacement=f.fake.get();
            assertEquals(1,replacement.offers.get());assertTrue(old.closed);f.client.refresh().get(2,TimeUnit.SECONDS);Thread.sleep(80);assertSame(replacement,f.fake.get());assertEquals(1,replacement.offers.get());
            replacement.listener.state("CONNECTED");await(()->f.manager.invitation(f.phoneIdentity.fingerprint)!=null);replacement.listener.state("CLOSED");await(()->replacement.closed);
            setLastIncoming(f.manager,0);f.server.accept(f.phoneDiscovery.seal(f.currentPcPresence(),offer,System.currentTimeMillis()),System.currentTimeMillis());f.client.refresh().get(2,TimeUnit.SECONDS);Thread.sleep(120);
            assertSame("A resealed replay must not restart an already consumed offer",replacement,f.fake.get());assertEquals(1,replacement.offers.get());
        }
    }
    @Test(timeout=10000) public void rateLimitedOfferKeepsDecryptedCodeUntilItCanBeAccepted()throws Exception {
        try(Fixture f=new Fixture()){
            setLastIncoming(f.manager,System.currentTimeMillis());long now=System.currentTimeMillis();String code=PairingCode.offer(f.phoneIdentity,"rate-limited-offer",new Invitation("127.0.0.1",f.phone.port(),f.phoneIdentity.fingerprint,f.phoneToken),now).encode();
            f.server.accept(f.phoneDiscovery.seal(f.currentPcPresence(),code,now),now);f.client.refresh().get(2,TimeUnit.SECONDS);Thread.sleep(80);assertNull(f.fake.get());
            setLastIncoming(f.manager,0);f.client.refresh().get(2,TimeUnit.SECONDS);await(()->f.fake.get()!=null);assertEquals(1,f.fake.get().offers.get());
        }
    }
    @Test(timeout=10000) public void expiredDeferredOfferDoesNotReviveWhenOldPairCloses()throws Exception {
        try(Fixture f=new Fixture()){
            CompletableFuture<Invitation> initial=f.manager.connect(f.phonePresence);PairingCode first=f.offer();f.answer(first,f.phoneIdentity,f.phoneDiscovery,f.phonePresence);initial.get(2,TimeUnit.SECONDS);FakeTransport old=f.fake.get();
            long expires=System.currentTimeMillis()+700;String code=PairingCode.offer(f.phoneIdentity,"expired-deferred-offer",new Invitation("127.0.0.1",f.phone.port(),f.phoneIdentity.fingerprint,f.phoneToken),expires-PairingCode.LIFETIME_MS).encode();
            f.server.accept(f.phoneDiscovery.seal(f.currentPcPresence(),code,System.currentTimeMillis()),System.currentTimeMillis());f.client.refresh().get(2,TimeUnit.SECONDS);Thread.sleep(Math.max(1,expires-System.currentTimeMillis()+100));
            old.listener.state("CLOSED");f.client.refresh().get(2,TimeUnit.SECONDS);await(()->old.closed);Thread.sleep(120);assertSame(old,f.fake.get());
        }
    }
    private static void setLastIncoming(DesktopPairingManager manager,long value)throws Exception {java.lang.reflect.Field incoming=DesktopPairingManager.class.getDeclaredField("lastIncoming");incoming.setAccessible(true);incoming.setLong(manager,value);}
    @Test(timeout=15000) public void backgroundOffersAndAnswersKeepEnoughTimeForNativeIceGathering()throws Exception {
        try(Fixture f=new Fixture(180_000)){
            CompletableFuture<Invitation> outgoing=backup(f.manager,f.phonePresence);f.offer();FakeTransport old=f.fake.get();
            assertTrue("Background offer must survive ICE gathering beyond30 seconds",remainingPairMillis(f.manager)>90_000);
            f.manager.cancelPending(f.phoneIdentity.fingerprint);await(()->old.closed);assertThrows(ExecutionException.class,()->outgoing.get(1,TimeUnit.SECONDS));
            long now=System.currentTimeMillis();String offer=PairingCode.offer(f.phoneIdentity,"incoming-native-offer",new Invitation("127.0.0.1",f.phone.port(),f.phoneIdentity.fingerprint,f.phoneToken),now).encode();
            f.server.accept(f.phoneDiscovery.seal(f.currentPcPresence(),offer,now),now);f.client.refresh().get(2,TimeUnit.SECONDS);await(()->f.fake.get()!=old);
            assertTrue("Background answer must survive ICE gathering beyond30 seconds",remainingPairMillis(f.manager)>90_000);
        }
    }
    private static long remainingPairMillis(DesktopPairingManager manager)throws Exception {
        java.lang.reflect.Field pending=DesktopPairingManager.class.getDeclaredField("pending");pending.setAccessible(true);Object pair=pending.get(manager);assertNotNull(pair);
        java.lang.reflect.Field deadline=pair.getClass().getDeclaredField("deadline");deadline.setAccessible(true);return TimeUnit.NANOSECONDS.toMillis(deadline.getLong(pair)-System.nanoTime());
    }
    @Test(timeout=15000) public void authenticatedIncomingOfferPreemptsUnrelatedBackgroundAttempt()throws Exception {
        try(Fixture f=new Fixture()){
            CompletableFuture<Invitation> backup=backup(f.manager,f.phonePresence);f.offer();FakeTransport old=f.fake.get();
            TlsIdentity incoming=identity();DiscoveryIdentity incomingDiscovery=new DiscoveryIdentity(incoming);long now=System.currentTimeMillis();
            DiscoveryPresence incomingPresence=incomingDiscovery.presence("GB",0,3_000_000,now);f.server.accept(incomingPresence,now);
            String offer=PairingCode.offer(incoming,"new-incoming-offer",new Invitation("127.0.0.1",f.phone.port(),incoming.fingerprint,TlsIdentity.randomSecret()),now).encode();
            f.server.accept(incomingDiscovery.seal(f.currentPcPresence(),offer,now),now);f.client.refresh().get(2,TimeUnit.SECONDS);
            await(()->f.fake.get()!=old);assertTrue(old.closed);assertThrows(ExecutionException.class,()->backup.get(1,TimeUnit.SECONDS));assertEquals("IDLE",f.manager.state());
        }
    }
    @Test(timeout=15000) public void explicitConnectionPreemptsOtherBackgroundAttemptWithoutChangingForegroundStage()throws Exception {
        try(Fixture f=new Fixture()){
            CompletableFuture<Invitation> backup=backup(f.manager,f.phonePresence);f.offer();FakeTransport old=f.fake.get();
            assertEquals("IDLE",f.manager.state());
            TlsIdentity other=identity();DiscoveryPresence recipient=new DiscoveryIdentity(other).presence("GB",0,3_000_000,System.currentTimeMillis());f.server.accept(recipient,System.currentTimeMillis());f.client.refresh().get(2,TimeUnit.SECONDS);
            CompletableFuture<Invitation> explicit=f.manager.connect(recipient);await(()->f.fake.get()!=old);
            assertTrue(old.closed);assertThrows(ExecutionException.class,()->backup.get(1,TimeUnit.SECONDS));assertFalse(explicit.isDone());
            explicit.cancel(true);
        }
    }
    @Test(timeout=15000) public void explicitSameTargetPromotesBackgroundAndLateCancellationDoesNotCloseEstablishedTransport()throws Exception {
        try(Fixture f=new Fixture()){
            CompletableFuture<Invitation> backup=backup(f.manager,f.phonePresence);PairingCode offer=f.offer();FakeTransport transport=f.fake.get();
            CompletableFuture<Invitation> explicit=f.manager.connect(f.phonePresence);await(()->!f.manager.state().equals("IDLE"));backup.cancel(true);f.answer(offer,f.phoneIdentity,f.phoneDiscovery,f.phonePresence);
            assertEquals(f.phoneIdentity.fingerprint,explicit.get(2,TimeUnit.SECONDS).fingerprint);assertSame(transport,f.fake.get());
            backup.cancel(true);Thread.sleep(50);assertFalse(transport.closed);assertNotNull(f.manager.invitation(f.phoneIdentity.fingerprint));
        }
    }
    private static CompletableFuture<Invitation> backup(DesktopPairingManager manager,DiscoveryPresence recipient){return manager.connectBackup(recipient);}
    @Test(timeout=120000) public void nativeAutomaticManagersExchangeEncryptedSdpAndCarryActualPinnedTlsTcp()throws Exception {
        org.junit.Assume.assumeTrue(System.getProperty("os.name","").startsWith("Windows"));
        TlsIdentity a=identity(),b=identity();DiscoveryIdentity da=new DiscoveryIdentity(a),db=new DiscoveryIdentity(b);DiscoveryStore bootstrap=new DiscoveryStore();
        try(Node na=new Node(a,new NetworkAccess(),address->true);Node nb=new Node(b,new NetworkAccess(),address->true)){
            na.start(0);nb.start(0);na.setMeasuredUpload(3_000_000);nb.setMeasuredUpload(3_000_000);
            try(DesktopDiscoveryClient ca=new DesktopDiscoveryClient(a.fingerprint,"https://bootstrap.example/v1/exchange",url->new Connection(url,bootstrap),()->da.presence("",na.clientCount(),na.measuredUpload(),System.currentTimeMillis()),1000);
                DesktopDiscoveryClient cb=new DesktopDiscoveryClient(b.fingerprint,"https://bootstrap.example/v1/exchange",url->new Connection(url,bootstrap),()->db.presence("",nb.clientCount(),nb.measuredUpload(),System.currentTimeMillis()),1000)){
                ca.refresh().get(2,TimeUnit.SECONDS);cb.refresh().get(2,TimeUnit.SECONDS);ca.refresh().get(2,TimeUnit.SECONDS);
                try(DesktopPairingManager ma=new DesktopPairingManager(na,a,da,ca,fp->{});DesktopPairingManager mb=new DesktopPairingManager(nb,b,db,cb,fp->{});
                    ServerSocket echo=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
                    ScheduledExecutorService poll=Executors.newSingleThreadScheduledExecutor();ExecutorService io=Executors.newSingleThreadExecutor();
                    try{
                        poll.scheduleWithFixedDelay(()->{ca.refresh();cb.refresh();},0,200,TimeUnit.MILLISECONDS);
                        Invitation invitation=ma.connect(ca.presence(b.fingerprint)).get(90,TimeUnit.SECONDS);
                        Future<?> reply=io.submit(()->{try(Socket target=echo.accept()){target.getOutputStream().write(target.getInputStream().readNBytes(4));target.getOutputStream().flush();}catch(IOException error){throw new UncheckedIOException(error);}});
                        try(Node.Session session=na.connect(invitation,()->{});Socket flow=session.openTcp("127.0.0.1",echo.getLocalPort())){
                            flow.setSoTimeout(10000);flow.getOutputStream().write(new byte[]{11,22,33,44});flow.getOutputStream().flush();assertArrayEquals(new byte[]{11,22,33,44},flow.getInputStream().readNBytes(4));
                        }
                        reply.get(3,TimeUnit.SECONDS);assertEquals(b.fingerprint,invitation.fingerprint);
                    }finally{poll.shutdownNow();io.shutdownNow();}
                }
            }
        }
    }
    @Test(timeout=15000) public void targetedEncryptedPairingBindsEphemeralCredentialAndCarriesActualPinnedTls()throws Exception {
        try(Fixture f=new Fixture()){
            CompletableFuture<Invitation> pending=f.manager.connect(f.phonePresence);PairingCode offer=f.offer();
            assertEquals(f.pcIdentity.fingerprint,offer.fingerprint);assertNotEquals(f.pc.invitation("127.0.0.1").secret,offer.invitation.secret);
            try(Node.TransportRegistration reverse=f.phone.registerPeerTransport(f.pcIdentity.fingerprint,()->new Socket("127.0.0.1",f.pc.port()),f.phoneToken)){
                f.answer(offer,f.phoneIdentity,f.phoneDiscovery,f.phonePresence);Invitation accepted=pending.get(3,TimeUnit.SECONDS);
                assertEquals(f.phoneIdentity.fingerprint,accepted.fingerprint);assertEquals(f.phoneToken,accepted.secret);assertEquals(1,f.fake.get().answers.get());
                try(Node.Session session=f.pc.connect(accepted,()->{})){assertTrue(session.isLive());}
            }
        }
    }
    @Test(timeout=15000) public void wrongSenderCannotHijackPendingTargetAndReplayedAnswerDoesNotReactivate()throws Exception {
        try(Fixture f=new Fixture()){
            CompletableFuture<Invitation> pending=f.manager.connect(f.phonePresence);PairingCode offer=f.offer();
            TlsIdentity stranger=identity();DiscoveryIdentity strangerDiscovery=new DiscoveryIdentity(stranger);DiscoveryPresence strangerPresence=strangerDiscovery.presence("",0,3_000_000,System.currentTimeMillis());
            f.server.accept(strangerPresence,System.currentTimeMillis());f.answer(offer,stranger,strangerDiscovery,strangerPresence);Thread.sleep(100);
            assertFalse(pending.isDone());assertEquals(0,f.fake.get().answers.get());
            String answer=f.answer(offer,f.phoneIdentity,f.phoneDiscovery,f.phonePresence);pending.get(3,TimeUnit.SECONDS);assertEquals(1,f.fake.get().answers.get());
            DiscoveryPresence pc=f.currentPcPresence();f.server.accept(f.phoneDiscovery.seal(pc,answer,System.currentTimeMillis()),System.currentTimeMillis());f.client.refresh().get(2,TimeUnit.SECONDS);Thread.sleep(100);
            assertEquals(1,f.fake.get().answers.get());assertEquals(1,f.pc.clientCount());
        }
    }
    @Test(timeout=15000) public void cancellationRevokesRegistrationAndIgnoresLateConnectedCallback()throws Exception {
        try(Fixture f=new Fixture()){
            CompletableFuture<Invitation> pending=f.manager.connect(f.phonePresence);PairingCode offer=f.offer();f.fake.get().holdConnected=true;
            f.answer(offer,f.phoneIdentity,f.phoneDiscovery,f.phonePresence);await(()->f.fake.get().answers.get()==1);
            f.manager.cancelPending(f.phoneIdentity.fingerprint);assertThrows(ExecutionException.class,()->pending.get(2,TimeUnit.SECONDS));
            f.fake.get().listener.state("CONNECTED");Thread.sleep(100);assertNull(f.manager.invitation(f.phoneIdentity.fingerprint));assertEquals(0,f.pc.clientCount());assertTrue(f.fake.get().closed);
        }
    }
    @Test(timeout=15000) public void cancelledFutureRetiresOnlyItsPairAndAllowsSamePeerRetry()throws Exception {
        try(Fixture f=new Fixture()){
            CompletableFuture<Invitation> first=f.manager.connect(f.phonePresence);f.offer();FakeTransport old=f.fake.get();
            assertTrue(first.cancel(true));await(()->old.closed);
            CompletableFuture<Invitation> retry=f.manager.connect(f.phonePresence);await(()->f.fake.get()!=old);FakeTransport current=f.fake.get();
            old.listener.state("FAILED");old.listener.state("CONNECTED");
            assertFalse(retry.isDone());assertFalse(current.closed);
            retry.cancel(true);await(()->current.closed);assertTrue(f.pc.isSharing());
        }
    }
    private static TlsIdentity identity()throws Exception {return IdentityStore.load(Files.createTempDirectory("uzelio-pair-identity-"));}
    private static void await(java.util.function.BooleanSupplier ready)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!ready.getAsBoolean() && System.nanoTime()<until)Thread.sleep(10);assertTrue(ready.getAsBoolean());}
    private static final class Fixture implements AutoCloseable {
        final TlsIdentity pcIdentity,phoneIdentity;final Node pc,phone;
        final DiscoveryIdentity pcDiscovery,phoneDiscovery;final DiscoveryStore server=new DiscoveryStore();
        final DiscoveryPresence phonePresence;final DesktopDiscoveryClient client;final DesktopPairingManager manager;final AtomicReference<FakeTransport> fake=new AtomicReference<>();final String phoneToken=TlsIdentity.randomSecret();
        Fixture()throws Exception {this(5000);}
        Fixture(long timeoutMillis)throws Exception {this(timeoutMillis,identity(),identity());}
        Fixture(long timeoutMillis,TlsIdentity pcIdentity,TlsIdentity phoneIdentity)throws Exception {
            this.pcIdentity=pcIdentity;this.phoneIdentity=phoneIdentity;pc=new Node(pcIdentity,new NetworkAccess(),a->true);phone=new Node(phoneIdentity,new NetworkAccess(),a->true);pcDiscovery=new DiscoveryIdentity(pcIdentity);phoneDiscovery=new DiscoveryIdentity(phoneIdentity);
            pc.start(0);phone.start(0);pc.setMeasuredUpload(3_000_000);phone.setMeasuredUpload(3_000_000);
            phonePresence=phoneDiscovery.presence("CA",0,3_000_000,System.currentTimeMillis());server.accept(phonePresence,System.currentTimeMillis());
            client=new DesktopDiscoveryClient(pcIdentity.fingerprint,"https://bootstrap.example/v1/exchange",url->new Connection(url,server),()->pcDiscovery.presence("",pc.clientCount(),pc.measuredUpload(),System.currentTimeMillis()),1000);
            client.refresh().get(2,TimeUnit.SECONDS);
            manager=new DesktopPairingManager(pc,pcIdentity,pcDiscovery,client,new DesktopPairingManager.Listener(){public void unavailable(String fp){}},(port,listener)->{FakeTransport t=new FakeTransport(listener,phone.port());fake.set(t);return t;},timeoutMillis);
        }
        PairingCode offer()throws Exception {
            AtomicReference<PairingCode> found=new AtomicReference<>();await(()->{try{client.refresh().get(1,TimeUnit.SECONDS);for(DiscoverySignal s:server.signalsFor(phoneIdentity.fingerprint,System.currentTimeMillis()))if(found.get()==null)found.set(PairingCode.parseAndVerify(phoneDiscovery.open(s,System.currentTimeMillis()),System.currentTimeMillis()));return found.get()!=null;}catch(Exception e){return false;}});return found.get();
        }
        DiscoveryPresence currentPcPresence(){return server.presences(System.currentTimeMillis()).stream().filter(p->p.fingerprint.equals(pcIdentity.fingerprint)).findFirst().orElseThrow();}
        String answer(PairingCode offer,TlsIdentity identity,DiscoveryIdentity discovery,DiscoveryPresence presence)throws Exception {
            String token=identity.fingerprint.equals(phoneIdentity.fingerprint)?phoneToken:TlsIdentity.randomSecret();
            PairingCode answer=PairingCode.answer(identity,"signed-answer",new Invitation("127.0.0.1",phone.port(),identity.fingerprint,token),offer,System.currentTimeMillis());
            server.accept(presence,System.currentTimeMillis());server.accept(discovery.seal(currentPcPresence(),answer.encode(),System.currentTimeMillis()),System.currentTimeMillis());client.refresh().get(2,TimeUnit.SECONDS);return answer.encode();
        }
        public void close(){manager.close();client.close();pc.close();phone.close();}
    }
    private static final class FakeTransport implements DesktopPairingManager.Transport {
        final DesktopPairingManager.TransportListener listener;final int targetPort;final AtomicInteger answers=new AtomicInteger(),offers=new AtomicInteger();volatile boolean holdConnected,closed;
        FakeTransport(DesktopPairingManager.TransportListener listener,int targetPort){this.listener=listener;this.targetPort=targetPort;}
        public void createOffer(){listener.description("offer","signed-offer");}public void acceptOffer(String sdp){offers.incrementAndGet();listener.description("answer","signed-answer");}
        public void acceptAnswer(String sdp){answers.incrementAndGet();if(!holdConnected)listener.state("CONNECTED");}
        public boolean isConnected(){return !closed && answers.get()>0 && !holdConnected;}public Socket openStream()throws IOException {if(closed)throw new IOException("Closed");return new Socket("127.0.0.1",targetPort);}
        public void close(){closed=true;}
    }
    private static final class Connection extends HttpsURLConnection {
        final DiscoveryStore server;final ByteArrayOutputStream request=new ByteArrayOutputStream();Connection(URL url,DiscoveryStore server){super(url);this.server=server;}
        public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}public OutputStream getOutputStream(){return request;}public int getResponseCode(){return 200;}
        public InputStream getInputStream()throws IOException{return new ByteArrayInputStream(server.exchange(request.toByteArray(),System.currentTimeMillis()));}
        public String getCipherSuite(){return "TEST_BOUNDARY";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return new Certificate[0];}public Principal getPeerPrincipal(){return ()->"test";}public Principal getLocalPrincipal(){return null;}
    }
}
