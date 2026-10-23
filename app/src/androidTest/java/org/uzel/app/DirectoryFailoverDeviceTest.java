package org.uzel.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import org.json.JSONObject;
import org.uzel.core.*;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Explicit device acceptance: no USB forwarding, no fabricated peer announcements. */
public class DirectoryFailoverDeviceTest extends InstrumentationTestCase {
    private static Object field(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    public void testRetiredSignalsAreNotRegossiped()throws Exception {
        long now=System.currentTimeMillis();TlsIdentity key=DeviceIdentity.load();DiscoveryIdentity identity=new DiscoveryIdentity(key);
        DiscoveryPresence presence=identity.presence("RU",0,100,now);
        String offer=PairingCode.offer(key,"v=0",new Invitation("127.0.0.1",9443,key.fingerprint,TlsIdentity.randomSecret()),now).encode();
        DiscoverySignal signal=identity.seal(presence,offer,now);DiscoveryStore remote=new DiscoveryStore();remote.accept(signal,now);
        for(boolean extended:new boolean[]{false,true}){
            byte[] request=extended?remote.exportFrameWithContacts(now):remote.exportFrame(now);
            DiscoveryStore local=new DiscoveryStore(),response=new DiscoveryStore();
            response.ingestFrame(AutoDiscoveryManager.exchangeFiltered(local,request,key.fingerprint,java.util.Collections.singleton(signal.id),now),now);
            assertTrue(local.signals(now).isEmpty());assertTrue(response.signals(now).isEmpty());
        }
    }
    public void testHold() throws Exception {
        Bundle args=((InstrumentationTestRunner)getInstrumentation()).getArguments();
        if(!"directory-failover".equals(args.getString("action","")))return;
        Context context=getInstrumentation().getTargetContext();
        NodeController controller=NodeController.get(context);
        assertFalse("Stop sharing before acceptance",controller.snapshot().sharing);
        String original=controller.snapshot().discoveryURL;
        File off=new File(context.getFilesDir(),"directory-audit-off"),stop=new File(context.getFilesDir(),"directory-audit-stop"),drop=new File(context.getFilesDir(),"directory-audit-drop");
        Files.deleteIfExists(off.toPath());Files.deleteIfExists(stop.toPath());
        Files.deleteIfExists(drop.toPath());
        Activity activity=null;boolean ready=false,freshAfterOutage=false,restored=false;long outage=0;String droppedTarget=null;Object droppedPair=null;
        int seconds=Integer.parseInt(args.getString("seconds","360"));
        assertTrue(seconds>=60&&seconds<=1800);
        try {
            activity=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            controller.startSharing();long end=SystemClock.elapsedRealtime()+seconds*1000L,nextStatus=0;
            while(SystemClock.elapsedRealtime()<end&&!stop.exists()) {
                NodeController.Snapshot state=controller.snapshot();
                Field owner=NodeController.class.getDeclaredField("automatic");owner.setAccessible(true);
                AutoDiscoveryManager manager=(AutoDiscoveryManager)owner.get(controller);
                int peers=manager==null?0:manager.backupPeerCount();String source=manager==null?"":manager.source();
                if(drop.exists()){
                    assertTrue("Drop only after primary outage",outage>0);
                    String target=new String(Files.readAllBytes(drop.toPath()),java.nio.charset.StandardCharsets.UTF_8).trim();
                    assertTrue("Exact public peer fingerprint",target.matches("[0-9a-f]{64}"));
                    assertTrue("A second backup must survive the dropped link",peers>=2);
                    Field pairingField=NodeController.class.getDeclaredField("pairing");pairingField.setAccessible(true);
                    PairingManager pairing=(PairingManager)pairingField.get(controller);CountDownLatch done=new CountDownLatch(1);
                    java.lang.reflect.Method submit=PairingManager.class.getDeclaredMethod("submit",Runnable.class);submit.setAccessible(true);
                    java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
                    java.util.concurrent.atomic.AtomicReference<Object> previous=new java.util.concurrent.atomic.AtomicReference<>();
                    submit.invoke(pairing,(Runnable)()->{try{
                        Field active=PairingManager.class.getDeclaredField("active");active.setAccessible(true);Object pair=((Map<?,?>)active.get(pairing)).get(target);
                        if(pair==null)throw new AssertionError("Target backup not established");
                        previous.set(pair);
                        java.lang.reflect.Method dispose=PairingManager.class.getDeclaredMethod("dispose",pair.getClass());dispose.setAccessible(true);dispose.invoke(pairing,pair);
                    }catch(Throwable error){failure.set(error);}finally{done.countDown();}});
                    assertTrue(done.await(5,TimeUnit.SECONDS));if(failure.get()!=null)throw new AssertionError(failure.get());
                    droppedTarget=target;droppedPair=previous.get();
                    Files.deleteIfExists(drop.toPath());
                    Bundle dropped=new Bundle();dropped.putString("stream","DROPPED_BACKUP "+target+"\n");getInstrumentation().sendStatus(0,dropped);
                }
                if(droppedTarget!=null&&!restored){
                    Field pf=NodeController.class.getDeclaredField("pairing");pf.setAccessible(true);PairingManager pm=(PairingManager)pf.get(controller);
                    java.lang.reflect.Method submit=PairingManager.class.getDeclaredMethod("submit",Runnable.class);submit.setAccessible(true);
                    CountDownLatch checked=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Object> current=new java.util.concurrent.atomic.AtomicReference<>();String target=droppedTarget;
                    submit.invoke(pm,(Runnable)()->{try{Field active=PairingManager.class.getDeclaredField("active");active.setAccessible(true);current.set(((Map<?,?>)active.get(pm)).get(target));}catch(Exception ignored){}finally{checked.countDown();}});
                    assertTrue(checked.await(5,TimeUnit.SECONDS));restored=current.get()!=null&&current.get()!=droppedPair;
                    if(restored){Bundle report=new Bundle();report.putString("stream","RESTORED_BACKUP_AFTER_OUTAGE "+target+"\n");getInstrumentation().sendStatus(0,report);}
                }
                if(peers>0&&"PEER".equals(source))ready=true;
                if(off.exists()&&outage==0){
                    assertTrue("Automatic backup must be established before outage",ready);
                    outage=System.currentTimeMillis();controller.setBootstrapUrl("https://127.0.0.1:1/v1/exchange");Files.deleteIfExists(off.toPath());
                }
                long newest=0;
                if(manager!=null){
                    Field cache=AutoDiscoveryManager.class.getDeclaredField("store");cache.setAccessible(true);
                    String own=DeviceIdentity.load().fingerprint;
                    for(DiscoveryPresence presence:((DiscoveryStore)cache.get(manager)).presences(System.currentTimeMillis()))if(!presence.fingerprint.equals(own))newest=Math.max(newest,presence.issuedAt);
                    if(outage>0&&newest>outage+5000&&"PEER".equals(source))freshAfterOutage=true;
                }
                if(SystemClock.elapsedRealtime()>=nextStatus){
                    PairingManager pmReport=(PairingManager)field(controller,"pairing");Bundle diagnostic=new Bundle();diagnostic.putString("stream","PAIRING pending="+(manager==null?"none":field(manager,"pending"))+" state="+(pmReport==null?"none":pmReport.state())+" error="+(pmReport==null?"":pmReport.diagnostic())+" catalog="+(manager==null?"none":((DiscoveryStore)field(manager,"store")).presences(System.currentTimeMillis()).stream().map(p->p.fingerprint).collect(java.util.stream.Collectors.joining(",")))+"\n");getInstrumentation().sendStatus(0,diagnostic);
                    Bundle report=new Bundle();report.putString("stream",new JSONObject().put("event","directory_audit").put("identity",DeviceIdentity.load().fingerprint).put("ready",ready).put("source",source).put("backupPeers",peers).put("savedDirectories",state.directoryContacts).put("discovery",state.discoveryState).put("sharing",state.sharing).put("vpn",state.vpnConnected).put("outage",outage>0).put("freshAfterOutage",freshAfterOutage).put("newest",newest).toString()+"\n");getInstrumentation().sendStatus(0,report);nextStatus=SystemClock.elapsedRealtime()+2000;
                }
                Thread.sleep(200);
            }
            assertTrue("No automatically established backup discovery link",ready);
            assertTrue("Outage marker was not applied",outage>0);
            assertTrue("Must receive newly signed remote data after primary becomes unreachable",freshAfterOutage);
            if(droppedTarget!=null)assertTrue("Dropped peer must establish a new connection through surviving backup",restored);
        } finally {
            controller.stopSharing();controller.setBootstrapUrl(original);context.getSharedPreferences("node",Context.MODE_PRIVATE).edit().commit();
            Files.deleteIfExists(off.toPath());Files.deleteIfExists(stop.toPath());
            Files.deleteIfExists(drop.toPath());
            if(activity!=null){Activity closing=activity;getInstrumentation().runOnMainSync(closing::finish);}
        }
    }
}
