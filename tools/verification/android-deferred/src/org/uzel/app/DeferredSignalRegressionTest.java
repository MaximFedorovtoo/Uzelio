package org.uzel.app;
import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;import org.junit.*;import static org.junit.Assert.*;import org.uzel.core.*;import org.uzel.app.traversal.TraversalTransport;
/** Real managers and signed/encrypted records; Android environment and ICE transport are fixtures. */
public final class DeferredSignalRegressionTest {
 long now;TlsIdentity own,remote;Node node;PairingManager pairing;AutoDiscoveryManager automatic;DiscoveryIdentity sender;DiscoveryPresence ownPresence;PhysicalNetwork physical;DiscoverySignal signal;PairingCode offer;DiscoveryStore store;Object old;Class<?> pairType;
 static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static void set(Object o,String name,Object v)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,v);}
 static void serial(Object o,String name,Runnable action)throws Exception{((ExecutorService)get(o,name)).submit(action).get(5,TimeUnit.SECONDS);}
 void process(long time)throws Exception{serial(automatic,"worker",()->{try{Method m=AutoDiscoveryManager.class.getDeclaredMethod("processSignals",long.class);m.setAccessible(true);m.invoke(automatic,time);}catch(Exception e){throw new RuntimeException(e);}});}
 void drain()throws Exception{serial(pairing,"serial",()->{});serial(automatic,"worker",()->{});serial(pairing,"serial",()->{});serial(automatic,"worker",()->{});}
 void closeOld()throws Exception{serial(pairing,"serial",()->{try{Method dispose=PairingManager.class.getDeclaredMethod("dispose",pairType);dispose.setAccessible(true);dispose.invoke(pairing,old);}catch(Exception e){throw new RuntimeException(e);}});}
 @Before public void setup()throws Exception{
  now=System.currentTimeMillis();own=FixtureIdentity.create();remote=FixtureIdentity.create();if(own.fingerprint.compareTo(remote.fingerprint)>0){TlsIdentity swap=own;own=remote;remote=swap;}DeviceIdentity.key=own;node=new Node(own,new NetworkAccess(),ip->true);node.start(0);physical=new PhysicalNetwork();
  pairing=new PairingManager(new android.content.Context(),physical,node,new PairingManager.Listener(){public void available(Invitation i){}public void unavailable(String f){}});
  automatic=new AutoDiscoveryManager(new android.content.Context(),node,physical,pairing,"https://example.test",false,Collections::emptyList,new AutoDiscoveryManager.Listener(){public void catalog(List<DiscoveryPresence> p){}public void failed(String f){}});
  serial(automatic,"worker",()->{});node.setMeasuredUpload(100000);
  DiscoveryIdentity recipient=(DiscoveryIdentity)get(automatic,"identity");sender=new DiscoveryIdentity(remote);ownPresence=recipient.presence("RU",0,100,now);DiscoveryPresence remotePresence=sender.presence("DE",0,100,now);
  String text=PairingCode.offer(remote,"v=0",new Invitation("127.0.0.1",9443,remote.fingerprint,TlsIdentity.randomSecret()),now).encode();offer=PairingCode.parseAndVerify(text,now);signal=sender.seal(ownPresence,text,now);store=(DiscoveryStore)get(automatic,"store");store.accept(ownPresence,now);store.accept(remotePresence,now);store.accept(signal,now);
  pairType=Class.forName("org.uzel.app.PairingManager$Pair");Constructor<?> ctor=pairType.getDeclaredConstructor();ctor.setAccessible(true);old=ctor.newInstance();set(old,"remote",PairingCode.parseAndVerify(text,now));
  serial(pairing,"serial",()->{try{((Map)get(pairing,"active")).put(remote.fingerprint,old);}catch(Exception e){throw new RuntimeException(e);}});TraversalTransport.accepted.set(0);
 }
 @After public void cleanup()throws Exception{if(automatic!=null)automatic.close();if(pairing!=null)pairing.close();if(node!=null)node.close();}
 @Test public void sameEncryptedOfferRetriesAfterOldActiveClosesAndAcceptsOnce()throws Exception{
  process(now);drain();assertEquals(0,TraversalTransport.accepted.get());assertEquals(1,((Map)get(automatic,"deferredSignals")).size());assertFalse((Boolean)get(old,"disposed"));
  closeOld();process(now+1);drain();assertEquals("Same decrypted signal must retry after old active closes",1,TraversalTransport.accepted.get());
  process(now+2);drain();assertEquals("Accepted signal is handled once",1,TraversalTransport.accepted.get());assertTrue(((Map)get(automatic,"deferredSignals")).isEmpty());
  PairingCode.ReplayWindow replay=(PairingCode.ReplayWindow)get(pairing,"replay");assertFalse(replay.claim(offer,now));
 }
 @Test public void expiredDeferredOfferCannotMutatePairing()throws Exception{
  process(now);drain();assertEquals(1,((Map)get(automatic,"deferredSignals")).size());process(signal.expiresAt);drain();
  assertTrue(((Map)get(automatic,"deferredSignals")).isEmpty());assertNull(get(pairing,"pending"));assertFalse((Boolean)get(old,"disposed"));assertEquals(0,TraversalTransport.accepted.get());
 }
 @Test public void explicitCancelRetiresDeferredButNaturalCloseDoesNot()throws Exception{
  process(now);drain();automatic.cancel(remote.fingerprint);drain();closeOld();process(now+1);drain();
  assertTrue(((Map)get(automatic,"deferredSignals")).isEmpty());assertTrue(((Map)get(automatic,"handled")).containsKey(remote.fingerprint+":"+signal.id));assertEquals(0,TraversalTransport.accepted.get());
  DiscoverySignal fresh=sender.seal(ownPresence,offer.encode(),now+1);store.accept(fresh,now+1);process(now+1);drain();assertEquals("A fresh signal id follows ordinary admission",1,TraversalTransport.accepted.get());
 }
 @Test public void queuedCancellationCannotCreatePendingAndOneAdmissionIsInFlight()throws Exception{
  closeOld();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);((ExecutorService)get(pairing,"serial")).submit(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(2,TimeUnit.SECONDS));
  try{for(int i=0;i<20;i++)process(now);assertNotNull(get(automatic,"incomingSignal"));assertEquals(1,((Map)get(automatic,"deferredSignals")).size());automatic.cancel(remote.fingerprint);serial(automatic,"worker",()->{});}finally{release.countDown();}
  drain();assertNull(get(pairing,"pending"));assertNull(get(automatic,"incomingSignal"));assertEquals(0,TraversalTransport.accepted.get());process(now+1);drain();assertEquals(0,TraversalTransport.accepted.get());
 }
 @Test public void rateDeferralDoesNotConsumeEncryptedReplay()throws Exception{
  closeOld();set(automatic,"lastIncoming",now);process(now);drain();assertTrue(((Map)get(automatic,"deferredSignals")).isEmpty());assertEquals(0,TraversalTransport.accepted.get());
  process(now+10001);drain();assertEquals(1,TraversalTransport.accepted.get());
 }
 @Test public void staleAdmissionCancellationPreservesNewerSameSenderPending()throws Exception{
  closeOld();Constructor<?> ctor=pairType.getDeclaredConstructor();ctor.setAccessible(true);Object newer=ctor.newInstance();PairingCode fresh=PairingCode.offer(remote,"v=0",new Invitation("127.0.0.1",9443,remote.fingerprint,TlsIdentity.randomSecret()),now);
  set(newer,"remote",fresh);set(newer,"targetFingerprint",remote.fingerprint);serial(pairing,"serial",()->{try{set(pairing,"pending",newer);}catch(Exception e){throw new RuntimeException(e);}});
  pairing.cancelTargeted(remote.fingerprint,new PairingManager.DescriptionSink(){public void description(String c){}public void failed(){}public void connected(){}});drain();assertSame(newer,get(pairing,"pending"));assertFalse((Boolean)get(newer,"disposed"));
 } @Test public void queuedAdmissionPreventsCycleStartingUnrelatedBackgroundOffer()throws Exception{
  closeOld();physical.response=store.exportFrame(now);assertNotNull(BackupPeerSelector.next(own.fingerprint,store.presences(now),Collections.emptySet(),now));
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);((ExecutorService)get(pairing,"serial")).submit(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(2,TimeUnit.SECONDS));
  try{
   serial(automatic,"worker",()->{try{Method cycle=AutoDiscoveryManager.class.getDeclaredMethod("cycle");cycle.setAccessible(true);cycle.invoke(automatic);}catch(Exception e){throw new RuntimeException(e);}});
   assertNotNull(get(automatic,"incomingSignal"));assertEquals("",get(automatic,"pending"));assertFalse((Boolean)get(automatic,"backgroundPending"));
  }finally{release.countDown();}
  drain();assertEquals(1,TraversalTransport.accepted.get());assertEquals(remote.fingerprint,get(automatic,"pending"));
 }}
