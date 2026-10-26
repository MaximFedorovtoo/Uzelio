package org.uzel.desktop.messenger;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.uzel.core.*;
import org.uzel.desktop.IdentityStore;
import org.uzel.messenger.*;
import org.uzel.messenger.chat.*;
import java.net.*;
import java.io.*;
import javax.net.ssl.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public final class ChatNetworkTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void twoRealTlsPeersExchangeEncryptedTextAndAuthenticatedReceipt()throws Exception {
        try(Peer a=peer();Peer b=peer()) {
            var ac=a.chat.addContact(b.chat.invitation("127.0.0.1"),"Bob");
            var bc=b.chat.addContact(a.chat.invitation("127.0.0.1"),"Alice");
            String id=a.chat.send(ac.id(),"Привет через сеть").id();
            await(()->a.chat.history(ac.id()).stream().anyMatch(m->m.id().equals(id)&&m.status().equals("delivered")));
            assertEquals("Привет через сеть",b.chat.history(bc.id()).get(0).text());
            assertFalse(b.chat.history(bc.id()).get(0).outgoing());
            a.chat.retryNow();
            assertEquals(1,b.chat.history(bc.id()).size());
            assertThrows(IllegalArgumentException.class,()->a.chat.send(ac.id(),"x".repeat(32769)));
        }
    }
    @Test public void invitationTamperingAndUnknownTlsPeerCannotCreateHistory()throws Exception {
        try(Peer a=peer();Peer b=peer();Peer stranger=peer()) {
            String code=b.chat.invitation("127.0.0.1");
            byte[] bytes=Base64.getUrlDecoder().decode(code.substring("uzelio-chat:1:".length()));
            bytes[30]^=1;
            String corrupt="uzelio-chat:1:"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            assertThrows(Exception.class,()->a.chat.addContact(corrupt,"tampered"));
            var contact=stranger.chat.addContact(code,"Bob");
            stranger.chat.send(contact.id(),"not allowed");
            stranger.chat.retryNow();
            assertTrue(b.chat.contacts().isEmpty());
            assertEquals("queued",stranger.chat.history(contact.id()).get(0).status());
        }
    }
    @Test public void lostAcknowledgementOfflineQueueAndDuplicateSurviveBothPeerRestarts()throws Exception {
        Path ar=tmp.newFolder().toPath(),br=tmp.newFolder().toPath();GateNetwork gate=new GateNetwork();
        Peer a=new Peer(ar,gate,0),b=new Peer(br,new NetworkAccess(),0);String aid,bid,id;int ap=a.chat.localPort(),bp=b.chat.localPort();
        try {
            aid=a.chat.addContact(b.chat.invitation("127.0.0.1"),"Bob").id();bid=b.chat.addContact(a.chat.invitation("127.0.0.1"),"Alice").id();
            id=a.chat.send(aid,"Lost ACK, durable history").id();
            var pending=a.runtime.chatRepository().pending().get(0);var destination=ChatInvite.decode(a.runtime.chatRepository().contact(aid).inviteCode());
            var wire=new SignalMessenger.Outbound(pending.cipherType(),pending.ciphertext());
            var ack1=rawExchange(a.identity,destination,wire);var ack2=rawExchange(a.identity,destination,wire);
            assertArrayEquals("Retransmission reuses encrypted ACK",ack1.body(),ack2.body());
            assertEquals(1,b.chat.history(bid).size());assertEquals("queued",a.chat.history(aid).get(0).status());
        }finally{a.close();b.close();}
        try(Peer restoredA=new Peer(ar,new NetworkAccess(),ap);Peer restoredB=new Peer(br,new NetworkAccess(),bp)) {
            restoredA.chat.retryNow();
            await(()->restoredA.chat.history(aid).stream().anyMatch(m->m.id().equals(id)&&m.status().equals("delivered")));
            assertEquals(1,restoredB.chat.history(bid).size());
            assertTrue(restoredA.runtime.chatRepository().pending().isEmpty());
        }
    }
    @Test public void tamperedCiphertextAndOversizedWireDoNotAdvanceHistory()throws Exception {
        GateNetwork gate=new GateNetwork();try(Peer a=new Peer(tmp.newFolder().toPath(),gate,0);Peer b=peer()) {
            String aid=a.chat.addContact(b.chat.invitation("127.0.0.1"),"Bob").id(),bid=b.chat.addContact(a.chat.invitation("127.0.0.1"),"Alice").id();
            a.chat.send(aid,"Only the authenticated payload");var pending=a.runtime.chatRepository().pending().get(0);var destination=ChatInvite.decode(a.runtime.chatRepository().contact(aid).inviteCode());
            byte[] corrupt=pending.ciphertext();corrupt[corrupt.length-1]^=1;
            assertThrows(Exception.class,()->rawExchange(a.identity,destination,new SignalMessenger.Outbound(pending.cipherType(),corrupt)));
            assertTrue(b.chat.history(bid).isEmpty());
            try(SSLSocket tls=connect(a.identity,destination)){new DataOutputStream(tls.getOutputStream()).writeInt(ChatWire.MAX_WIRE+1);tls.getOutputStream().flush();assertEquals(-1,tls.getInputStream().read());}
            assertTrue(b.chat.history(bid).isEmpty());gate.open=true;a.chat.retryNow();await(()->!b.chat.history(bid).isEmpty());
            assertEquals("Only the authenticated payload",b.chat.history(bid).get(0).text());
        }
    }
    @Test public void authenticatedButUnknownAcknowledgementRollsBackRatchetAndCannotDeliver()throws Exception {
        try(Peer b=peer()) {
            Path ar=tmp.newFolder().toPath();TlsIdentity identity=IdentityStore.load(ar);AtomicBoolean forged=new AtomicBoolean(true);AtomicInteger attempts=new AtomicInteger();
            try(var runtime=DesktopMessenger.open(ar);var service=new ChatService(runtime,identity,(auth,receiver)->{
                var delegate=ChatTransport.pinned(identity,new NetworkAccess(),InetAddress.getLoopbackAddress(),0,auth,receiver);
                return new ChatTransport(){
                    public int localPort(){return delegate.localPort();}
                    public SignalMessenger.Outbound exchange(ChatInvite destination,SignalMessenger.Outbound message)throws Exception {
                        var legitimate=delegate.exchange(destination,message);attempts.incrementAndGet();
                        if(!forged.get())return legitimate;
                        return b.runtime.messenger().send(runtime.messenger().localAddress(),"forged_ack",ChatWire.encode(new ChatWire.Frame(ChatWire.ACK,"unknown-message",0,"")));
                    }
                    public void close(){delegate.close();}
                };
            })) {
                String aid=service.addContact(b.chat.invitation("127.0.0.1"),"Bob").id();
                b.chat.addContact(service.invitation("127.0.0.1"),"Alice");
                service.send(aid,"Receipt must reference this message");await(()->attempts.get()>0);Thread.sleep(100);
                assertEquals("queued",service.history(aid).get(0).status());assertEquals(1,runtime.chatRepository().pending().size());
                forged.set(false);service.retryNow();await(()->service.history(aid).get(0).status().equals("delivered"));
            }
        }
    }
    @Test public void changedServerCertificateNeverReplacesPinnedContact()throws Exception {
        AtomicInteger connections=new AtomicInteger();NetworkAccess network=new NetworkAccess(){@Override public Socket open(String host,int port)throws IOException {connections.incrementAndGet();return super.open(host,port);}};
        try(Peer a=new Peer(tmp.newFolder().toPath(),network,0);Peer b=peer()) {
            String aid=a.chat.addContact(b.chat.invitation("127.0.0.1"),"Bob").id(),bid=b.chat.addContact(a.chat.invitation("127.0.0.1"),"Alice").id();int port=b.chat.localPort();b.chat.close();
            TlsIdentity replacement=IdentityStore.load(tmp.newFolder().toPath());
            try(var changed=new ChatService(b.runtime,replacement,new NetworkAccess(),InetAddress.getLoopbackAddress(),port)) {
                a.chat.send(aid,"Pinned certificate required");await(()->connections.get()>0);Thread.sleep(200);
                assertEquals("queued",a.chat.history(aid).get(0).status());assertTrue(changed.history(bid).isEmpty());
                assertThrows(SecurityException.class,()->a.chat.addContact(changed.invitation("127.0.0.1"),"Replacement"));
            }
            try(var restored=new ChatService(b.runtime,b.identity,new NetworkAccess(),InetAddress.getLoopbackAddress(),port)) {
                a.chat.retryNow();await(()->a.chat.history(aid).get(0).status().equals("delivered"));assertEquals(1,restored.history(bid).size());
            }
        }
    }
    @Test public void simultaneousFirstMessagesAndShutdownLeaveNoResetOrLostHistory()throws Exception {
        try(Peer a=peer();Peer b=peer()) {
            String aid=a.chat.addContact(b.chat.invitation("127.0.0.1"),"Bob").id(),bid=b.chat.addContact(a.chat.invitation("127.0.0.1"),"Alice").id();
            ExecutorService senders=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
            try {
                Future<?> af=senders.submit(()->{try{start.await();a.chat.send(aid,"simultaneous Alice");}catch(Exception e){throw new CompletionException(e);}});
                Future<?> bf=senders.submit(()->{try{start.await();b.chat.send(bid,"simultaneous Bob");}catch(Exception e){throw new CompletionException(e);}});
                start.countDown();af.get(5,TimeUnit.SECONDS);bf.get(5,TimeUnit.SECONDS);
                await(()->a.chat.history(aid).size()==2&&b.chat.history(bid).size()==2&&a.runtime.chatRepository().pending().isEmpty()&&b.runtime.chatRepository().pending().isEmpty());
                ChatInvite destination=ChatInvite.decode(a.runtime.chatRepository().contact(aid).inviteCode());
                try(SSLSocket idle=connect(a.identity,destination)) {
                    long before=System.nanoTime();b.chat.close();assertTrue("Shutdown interrupts an idle authenticated socket",System.nanoTime()-before<3_000_000_000L);
                }
                assertEquals(2,b.runtime.chatRepository().history(bid).size());
            }finally{senders.shutdownNow();}
        }
    }
    @Test public void protocolMaximumMessageIdReceivesStableAcknowledgement()throws Exception {
        try(Peer a=peer();Peer b=peer()) {
            String aid=a.chat.addContact(b.chat.invitation("127.0.0.1"),"Bob").id(),bid=b.chat.addContact(a.chat.invitation("127.0.0.1"),"Alice").id();
            ChatInvite destination=ChatInvite.decode(a.runtime.chatRepository().contact(aid).inviteCode());String id="m".repeat(128);
            var wire=a.runtime.messenger().send(destination.address(),"long_id",ChatWire.encode(new ChatWire.Frame(ChatWire.TEXT,id,System.currentTimeMillis(),"full protocol id")));
            var ack=rawExchange(a.identity,destination,wire);var received=a.runtime.messenger().receive(destination.address(),ack.type(),ack.body());
            assertEquals(id,ChatWire.decode(received.plaintext()).id());assertEquals(1,b.chat.history(bid).size());
            assertThrows(IOException.class,()->ChatWire.encode(new ChatWire.Frame(ChatWire.TEXT,"bad_unicode",0,"\ud800")));
        }
    }
    @Test public void absoluteDeadlineReclaimsIdleHandshakeAndTricklingTlsSlots()throws Exception {
        try(Peer a=peer();Peer b=new Peer(tmp.newFolder().toPath(),new NetworkAccess(),0,500)) {
            String aid=a.chat.addContact(b.chat.invitation("127.0.0.1"),"Bob").id(),bid=b.chat.addContact(a.chat.invitation("127.0.0.1"),"Alice").id();
            ChatInvite destination=ChatInvite.decode(a.runtime.chatRepository().contact(aid).inviteCode());
            List<Socket> idle=new ArrayList<>();
            try {
                for(int i=0;i<4;i++){Socket socket=new Socket("127.0.0.1",b.chat.localPort());socket.setSoTimeout(2000);idle.add(socket);}
                Thread.sleep(750);
                for(Socket socket:idle)assertEquals("Absolute deadline closes an idle TLS handshake",-1,socket.getInputStream().read());
            }finally{for(Socket socket:idle)socket.close();}
            a.chat.send(aid,"after idle handshakes");await(()->a.runtime.chatRepository().pending().isEmpty());
            List<SSLSocket> trickling=new ArrayList<>();
            try {
                for(int i=0;i<4;i++){SSLSocket socket=connect(a.identity,destination);trickling.add(socket);var out=new DataOutputStream(socket.getOutputStream());out.writeInt(131080);out.writeInt(0x55435731);out.writeInt(2);out.flush();}
                for(int tick=0;tick<8;tick++){for(SSLSocket socket:trickling)try{socket.getOutputStream().write(0);socket.getOutputStream().flush();}catch(IOException closedByDeadline){}Thread.sleep(100);}
                for(SSLSocket socket:trickling)try{assertEquals("Trickling cannot extend the absolute lifetime",-1,socket.getInputStream().read());}catch(IOException closedByDeadline){}
            }finally{for(SSLSocket socket:trickling)socket.close();}
            a.chat.send(aid,"after trickling TLS");await(()->a.runtime.chatRepository().pending().isEmpty());assertEquals(2,b.chat.history(bid).size());
        }
    }
    private static final class GateNetwork extends NetworkAccess {volatile boolean open;@Override public Socket open(String host,int port)throws IOException {if(!open)throw new IOException("offline test network");return super.open(host,port);}}
    private static SSLSocket connect(TlsIdentity identity,ChatInvite destination)throws Exception {var tls=(SSLSocket)identity.context(destination.tlsFingerprint()).getSocketFactory().createSocket(destination.host(),destination.port());tls.setSoTimeout(5000);tls.startHandshake();return tls;}
    private static SignalMessenger.Outbound rawExchange(TlsIdentity identity,ChatInvite destination,SignalMessenger.Outbound wire)throws Exception {try(var tls=connect(identity,destination)){ChatWire.write(tls.getOutputStream(),wire);return ChatWire.read(tls.getInputStream());}}
    private Peer peer()throws Exception {return new Peer(tmp.newFolder().toPath());}
    private static final class Peer implements AutoCloseable {
        final MessengerRuntime runtime;final ChatService chat;final TlsIdentity identity;
        Peer(Path path)throws Exception {this(path,new NetworkAccess(),0);}
        Peer(Path path,NetworkAccess network,int port)throws Exception {this(path,network,port,30000);}
        Peer(Path path,NetworkAccess network,int port,long deadlineMillis)throws Exception {runtime=DesktopMessenger.open(path);identity=IdentityStore.load(path);chat=new ChatService(runtime,identity,(auth,receiver)->ChatTransport.pinned(identity,network,InetAddress.getLoopbackAddress(),port,auth,receiver,deadlineMillis));}
        public void close()throws Exception {chat.close();runtime.close();}
    }
    private interface Condition {boolean get()throws Exception;}
    private static void await(Condition condition)throws Exception {long deadline=System.nanoTime()+10_000_000_000L;while(!condition.get()){if(System.nanoTime()>deadline)fail("Timed out waiting for delivery");Thread.sleep(25);}}
}
