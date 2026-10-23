package org.uzel.desktop;

import org.uzel.core.*;
import java.nio.file.*;
import java.net.*;
import java.io.*;
import java.util.*;

/** Real desktop participant; no OS routing changes. Local markers control an isolated acceptance run. */
public final class DesktopDirectoryFailoverProbe {
    private static Object observedPair;
    private static String pairing(FederationBackend backend)throws Exception {
        var owner=FederationBackend.class.getDeclaredField("pairing");owner.setAccessible(true);Object manager=owner.get(backend);if(manager==null)return "none";
        var executor=DesktopPairingManager.class.getDeclaredField("serial");executor.setAccessible(true);
        return ((java.util.concurrent.ExecutorService)executor.get(manager)).submit(()->{
            var pending=DesktopPairingManager.class.getDeclaredField("pending");pending.setAccessible(true);Object pair=pending.get(manager);if(pair==null)return "idle";
            var target=pair.getClass().getDeclaredField("target");target.setAccessible(true);
            var deadline=pair.getClass().getDeclaredField("deadline");deadline.setAccessible(true);
            if(observedPair!=pair){observedPair=pair;var future=pair.getClass().getDeclaredField("completion");future.setAccessible(true);((java.util.concurrent.CompletableFuture<?>)future.get(pair)).whenComplete((value,error)->System.out.println("PAIR_RESULT "+(error==null?"CONNECTED":error.toString())));}
            return target.get(pair)+":"+java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(deadline.getLong(pair)-System.nanoTime());
        }).get(5,java.util.concurrent.TimeUnit.SECONDS);
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        Path off=root.resolve("primary-off"),stop=root.resolve("stop");Files.deleteIfExists(off);Files.deleteIfExists(stop);
        TlsIdentity identity=IdentityStore.load(root);
        DesktopDiscoveryClient client=new DesktopDiscoveryClient(identity.fingerprint,DesktopDiscoveryClient.defaultEndpoint(),url->{
            if(Files.exists(off))throw new IOException("Primary unavailable for acceptance");
            return (HttpURLConnection)url.openConnection(Proxy.NO_PROXY);
        },()->null,5000);
        FederationBackend.ApplicationRouting routing=new FederationBackend.ApplicationRouting(){
            public WindowsAppRouter.Availability availability(){return new WindowsAppRouter.Availability(true,true,"");}
            public WindowsAppRouter.Snapshot snapshot(){return new WindowsAppRouter.Snapshot(false,"OFF","");}
            public void activate(List<Path> apps,DesktopTrafficService.ProxyEndpoint proxy){throw new AssertionError("Background directory link must not activate OS routing");}
            public void deactivate(){}public void close(){}
        };
        boolean ready=false,fresh=false;long outage=0;
        try(FederationBackend backend=new FederationBackend(root,identity,new DesktopTrafficService(identity),0,routing,client)){
            backend.start(new DesktopConfig("localhost",17779,false));backend.startSharing();
            long end=System.currentTimeMillis()+1_800_000;
            while(System.currentTimeMillis()<end&&!Files.exists(stop)){
                long newest=client.presences().stream().mapToLong(p->p.issuedAt).max().orElse(0);
                int live=client.livePeerExchanges();if(live>0)ready=true;
                if(Files.exists(off)&&outage==0){if(!ready)throw new AssertionError("Primary disabled before backup");outage=System.currentTimeMillis();}
                if(outage>0&&live>0&&newest>outage+5000)fresh=true;
                System.out.println("DIRECTORY identity="+identity.fingerprint+" ready="+ready+" live="+live+" backups="+backend.backupPeerCount()+" contacts="+client.contacts().size()+" state="+client.status().state()+" outage="+(outage>0)+" fresh="+fresh+" newest="+newest+" pairing="+pairing(backend)+" signals="+client.signals().stream().map(s->s.senderFingerprint.substring(0,8)+":"+(System.currentTimeMillis()-s.issuedAt)).toList());System.out.flush();
                Thread.sleep(2000);
            }
            if(!ready||outage==0||!fresh)throw new AssertionError("Unverified desktop failover ready="+ready+" outage="+outage+" fresh="+fresh);
            System.out.println("DESKTOP_DIRECTORY_FAILOVER_OK");
        }
    }
}
