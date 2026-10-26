package org.uzel.desktop.traversal;

import org.junit.*;
import org.uzel.core.*;
import org.uzel.desktop.IdentityStore;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** uzel.chat.v1 streams must reach the chat listener, not the VPN node port. */
public class TraversalChatStreamTest {
    @Test(timeout=120000) public void chatStreamsAreRoutedToTheChatPort() throws Exception {
        Assume.assumeTrue(System.getProperty("os.name","").startsWith("Windows"));
        TlsIdentity ka=IdentityStore.load(Files.createTempDirectory("chat-ice-a-"));
        TlsIdentity kb=IdentityStore.load(Files.createTempDirectory("chat-ice-b-"));
        try(Node a=new Node(ka,new NetworkAccess(),address->true);Node b=new Node(kb,new NetworkAccess(),address->true);
            ServerSocket chatListener=new ServerSocket(0,4,InetAddress.getLoopbackAddress())){
            a.start(0);b.start(0);a.setPublicMode(true);b.setPublicMode(true);
            a.setMeasuredUpload(3_000_000);b.setMeasuredUpload(3_000_000);
            try(Link link=new Link(a,b,ka,kb,chatListener.getLocalPort())){
                link.connect();
                byte[] payload=new byte[4096];new Random(11).nextBytes(payload);
                try(Socket stream=link.openChatA()){
                    stream.setSoTimeout(30000);
                    stream.getOutputStream().write(payload);stream.getOutputStream().flush();
                    try(Socket accepted=chatListener.accept()){
                        accepted.setSoTimeout(30000);
                        byte[] actual=accepted.getInputStream().readNBytes(payload.length);
                        assertArrayEquals("chat frame must arrive at the chat port",payload,actual);
                        accepted.getOutputStream().write(actual);accepted.getOutputStream().flush();
                    }
                    assertArrayEquals("return path must work",payload,stream.getInputStream().readNBytes(payload.length));
                }
            }
        }
    }

    private static final class Link implements AutoCloseable {
        final Class<?> type;final Object a,b;final Invitation ia,ib;final TlsIdentity ka,kb;
        final CountDownLatch connected=new CountDownLatch(2);
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final AtomicReference<PairingCode> offer=new AtomicReference<>();
        final Node.TransportRegistration ra,rb;
        Link(Node na,Node nb,TlsIdentity ka,TlsIdentity kb,int chatPort)throws Exception {
            this.ka=ka;this.kb=kb;type=Class.forName("org.uzel.desktop.traversal.DesktopTraversalTransport");
            ia=new Invitation("127.0.0.1",na.port(),ka.fingerprint,TlsIdentity.randomSecret());
            ib=new Invitation("127.0.0.1",nb.port(),kb.fingerprint,TlsIdentity.randomSecret());
            Class<?> observer=Class.forName(type.getName()+"$Listener");
            a=type.getConstructor(int.class,int.class,observer).newInstance(na.port(),chatPort,listener(observer,true));
            b=type.getConstructor(int.class,int.class,observer).newInstance(nb.port(),chatPort,listener(observer,false));
            ra=na.registerPeerTransport(kb.fingerprint,this::openA,ia.secret);
            rb=nb.registerPeerTransport(ka.fingerprint,this::openB,ib.secret);
        }
        private Object listener(Class<?> observer,boolean first){
            return java.lang.reflect.Proxy.newProxyInstance(observer.getClassLoader(),new Class<?>[]{observer},(proxy,method,args)->{
                try{
                    if(method.getName().equals("description")){
                        long now=System.currentTimeMillis();
                        if(first){
                            PairingCode signed=PairingCode.offer(ka,(String)args[1],ia,now);
                            PairingCode verified=PairingCode.parseAndVerify(signed.encode(),now);offer.set(verified);
                            type.getMethod("acceptOffer",String.class).invoke(b,verified.sdp);
                        }else{
                            PairingCode signed=PairingCode.answer(kb,(String)args[1],ib,offer.get(),now);
                            PairingCode verified=PairingCode.parseAndVerify(signed.encode(),now);
                            PairingCode.verifyAnswer(verified,offer.get(),now);
                            type.getMethod("acceptAnswer",String.class).invoke(a,verified.sdp);
                        }
                    }else if(method.getName().equals("state") && "CONNECTED".equals(args[0]))connected.countDown();
                    else if(method.getName().equals("failed"))failure.compareAndSet(null,new IOException((String)args[0]));
                }catch(Throwable error){failure.compareAndSet(null,error);connected.countDown();connected.countDown();}
                return null;
            });
        }
        void connect()throws Exception {
            type.getMethod("createOffer").invoke(a);
            assertTrue("Native pair timed out: "+failure.get(),connected.await(45,TimeUnit.SECONDS));
            if(failure.get()!=null)throw new AssertionError("Native pair failed",failure.get());
        }
        Socket openChatA()throws IOException{return open(a,"openChatStream");}
        Socket openA()throws IOException{return open(a,"openStream");}
        Socket openB()throws IOException{return open(b,"openStream");}
        Socket open(Object transport,String method)throws IOException{
            try{return (Socket)type.getMethod(method).invoke(transport);}
            catch(java.lang.reflect.InvocationTargetException error){if(error.getCause() instanceof IOException io)throw io;throw new IOException(error.getCause());}
            catch(Exception error){throw new IOException(error);}
        }
        public void close()throws Exception{ra.close();rb.close();((AutoCloseable)a).close();((AutoCloseable)b).close();}
    }
}
