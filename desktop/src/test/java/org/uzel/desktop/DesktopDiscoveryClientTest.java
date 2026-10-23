package org.uzel.desktop;

import org.junit.Test;
import org.uzel.core.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class DesktopDiscoveryClientTest {
    @Test(timeout=5000) public void closeImmediatelyClearsOnlineStateAndCannotRefresh()throws Exception {
        TlsIdentity self=identity();DesktopDiscoveryClient client=new DesktopDiscoveryClient(self.fingerprint,"https://bootstrap.example/v1/exchange",url->new Connection(url,new DiscoveryStore()),()->null,1000);
        try{assertTrue(client.refresh().get(2,TimeUnit.SECONDS));client.close();assertEquals("NOT_STARTED",client.status().state());assertFalse(client.refresh().get(1,TimeUnit.SECONDS));}finally{client.close();}
    }
    @Test public void corruptContactHintsDoNotDisableConfiguredBootstrap()throws Exception {
        TlsIdentity self=identity();Path root=Files.createTempDirectory("desktop-discovery-corrupt-hints-");
        Files.writeString(root.resolve("discovery.properties"),"bootstrapUrl=https://bootstrap.example/v1/exchange\n");Files.write(root.resolve("discovery-contacts.bin"),new byte[]{1,2,3});
        try(DesktopDiscoveryClient client=DesktopDiscoveryClient.configured(root,self.fingerprint)){assertEquals("NOT_STARTED",client.status().state());}
    }
    @Test(timeout=10000) public void actualSignedCatalogShowsOtherAndroidIdentityAndExpiresWithoutInventingRoute()throws Exception {
        TlsIdentity self=identity(),phone=identity();DiscoveryStore bootstrap=new DiscoveryStore();long expiry=System.currentTimeMillis()+1500;
        bootstrap.accept(new DiscoveryIdentity(phone).presence("CA",1,9_000_000,expiry-120_000),System.currentTimeMillis());
        DiscoveryIdentity own=new DiscoveryIdentity(self);AtomicReference<Connection> connection=new AtomicReference<>();
        try(DesktopDiscoveryClient client=new DesktopDiscoveryClient(self.fingerprint,"https://bootstrap.example/v1/exchange",url->{Connection next=new Connection(url,bootstrap);connection.set(next);return next;},()->own.presence("",0,6_000_000,System.currentTimeMillis()),1000)){
            assertTrue(client.refresh().get(2,TimeUnit.SECONDS));assertEquals("ONLINE",client.status().state());
            assertEquals(1,client.presences().size());assertEquals(phone.fingerprint,client.presences().get(0).fingerprint);assertEquals("CA",client.presences().get(0).country);
            assertEquals(1,client.status().verifiedDevices());assertFalse(connection.get().getInstanceFollowRedirects());assertEquals("POST",connection.get().getRequestMethod());
            assertTrue(bootstrap.presences(System.currentTimeMillis()).stream().anyMatch(p->p.fingerprint.equals(self.fingerprint)));
            Thread.sleep(Math.max(0,expiry-System.currentTimeMillis()+50));assertTrue(client.presences().isEmpty());assertEquals(0,client.status().verifiedDevices());
        }
    }
    @Test(timeout=10000) public void failedOrTamperedReplyIsDifferentFromSuccessfulEmptyCatalogAndDoesNotDestroyFreshObservations()throws Exception {
        TlsIdentity self=identity(),phone=identity();DiscoveryStore bootstrap=new DiscoveryStore();bootstrap.accept(new DiscoveryIdentity(phone).presence("RU",0,3_000_000,System.currentTimeMillis()),System.currentTimeMillis());
        AtomicInteger mode=new AtomicInteger();
        try(DesktopDiscoveryClient client=new DesktopDiscoveryClient(self.fingerprint,"https://bootstrap.example/v1/exchange",url->{Connection next=new Connection(url,bootstrap);if(mode.get()==1)next.status=302;if(mode.get()==2)next.body=new byte[]{1,2,3};return next;},()->null,1000)){
            assertTrue(client.refresh().get(2,TimeUnit.SECONDS));assertEquals(1,client.presences().size());
            mode.set(1);assertFalse(client.refresh().get(2,TimeUnit.SECONDS));assertEquals("UNAVAILABLE",client.status().state());assertEquals(1,client.presences().size());
            mode.set(2);assertFalse(client.refresh().get(2,TimeUnit.SECONDS));assertEquals("UNAVAILABLE",client.status().state());assertEquals(1,client.presences().size());
        }
        try(DesktopDiscoveryClient empty=new DesktopDiscoveryClient(self.fingerprint,"https://bootstrap.example/v1/exchange",url->new Connection(url,new DiscoveryStore()),()->null,1000)){
            assertTrue(empty.refresh().get(2,TimeUnit.SECONDS));assertEquals("ONLINE",empty.status().state());assertEquals(0,empty.status().verifiedDevices());
        }
    }
    @Test(timeout=10000) public void boundsResponseAndDeadlineAndCloseRejectsLateCatalog()throws Exception {
        TlsIdentity self=identity();CountDownLatch waiting=new CountDownLatch(1),aborted=new CountDownLatch(1);
        try(DesktopDiscoveryClient oversized=new DesktopDiscoveryClient(self.fingerprint,"https://bootstrap.example/v1/exchange",url->{Connection next=new Connection(url,new DiscoveryStore());next.body=new byte[DiscoveryStore.MAX_FRAME+1];return next;},()->null,1000)){
            assertFalse(oversized.refresh().get(2,TimeUnit.SECONDS));assertTrue(oversized.presences().isEmpty());
        }
        DesktopDiscoveryClient blocked=new DesktopDiscoveryClient(self.fingerprint,"https://bootstrap.example/v1/exchange",url->new Connection(url,new DiscoveryStore()){
            public InputStream getInputStream(){waiting.countDown();return new InputStream(){public int read()throws IOException {try{aborted.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}throw new IOException("Disconnected");}};}
            public void disconnect(){aborted.countDown();}
        },()->null,100);
        try{Future<Boolean> pending=blocked.refresh();assertTrue(waiting.await(1,TimeUnit.SECONDS));assertFalse(pending.get(2,TimeUnit.SECONDS));assertEquals("UNAVAILABLE",blocked.status().state());}
        finally{blocked.close();}
        assertThrows(IllegalArgumentException.class,()->DesktopDiscoveryClient.validateEndpoint("http://bootstrap.example/v1/exchange"));
        for(String url:List.of("https://u:p@bootstrap.example/v1/exchange","https://bootstrap.example/wrong","https://bootstrap.example/v1/exchange?q=x","https://bootstrap.example/v1/exchange#x"))
            assertThrows(url,IllegalArgumentException.class,()->DesktopDiscoveryClient.validateEndpoint(url));
    }
    @Test(timeout=10000) public void pauseBlocksStaleRefreshUntilExplicitResumeAndRejectsLateReply()throws Exception {
        TlsIdentity self=identity(),phone=identity();DiscoveryStore bootstrap=new DiscoveryStore();bootstrap.accept(new DiscoveryIdentity(phone).presence("CA",0,3_000_000,System.currentTimeMillis()),System.currentTimeMillis());
        CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger opened=new AtomicInteger();
        try(DesktopDiscoveryClient client=new DesktopDiscoveryClient(self.fingerprint,"https://bootstrap.example/v1/exchange",url->{opened.incrementAndGet();return new Connection(url,bootstrap){
            public InputStream getInputStream()throws IOException {byte[] bytes=bootstrap.exchange(request.toByteArray(),System.currentTimeMillis());return new ByteArrayInputStream(bytes){public synchronized int read(byte[] buffer,int offset,int count){reading.countDown();try{release.await();}catch(InterruptedException ignored){}return super.read(buffer,offset,count);}};}
        };},()->null,1000)){
            Future<Boolean> pending=client.refresh();assertTrue(reading.await(1,TimeUnit.SECONDS));client.pause();release.countDown();assertFalse(pending.get(2,TimeUnit.SECONDS));assertTrue(client.presences().isEmpty());
            assertFalse(client.refresh().get(1,TimeUnit.SECONDS));client.refreshIfDue();assertEquals(1,opened.get());assertEquals("NOT_STARTED",client.status().state());
            client.resume();assertTrue(client.refresh().get(2,TimeUnit.SECONDS));assertEquals(2,opened.get());assertEquals(1,client.presences().size());
        }finally{release.countDown();}
    }
    private static TlsIdentity identity()throws Exception {return IdentityStore.load(Files.createTempDirectory("uzelio-discovery-identity-"));}
    private static class Connection extends HttpsURLConnection {
        final DiscoveryStore bootstrap;final ByteArrayOutputStream request=new ByteArrayOutputStream();int status=200;byte[] body;
        Connection(URL url,DiscoveryStore store){super(url);bootstrap=store;}
        public void disconnect(){} public boolean usingProxy(){return false;}public void connect(){}
        public OutputStream getOutputStream(){return request;}
        public int getResponseCode(){return status;}
        public long getContentLengthLong(){return body==null?-1:body.length;}
        public InputStream getInputStream()throws IOException {return new ByteArrayInputStream(body==null?bootstrap.exchange(request.toByteArray(),System.currentTimeMillis()):body);}
        public String getCipherSuite(){return "TEST_BOUNDARY";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return new Certificate[0];}
        public Principal getPeerPrincipal(){return ()->"test";}public Principal getLocalPrincipal(){return null;}
    }
}
