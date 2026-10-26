package org.uzel.messenger.chat;

import org.uzel.core.*;
import org.uzel.messenger.SignalMessenger;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Transport extension point: only end-to-end Signal frames cross this boundary. */
public interface ChatTransport extends AutoCloseable {
    int localPort();
    SignalMessenger.Outbound exchange(ChatInvite destination,SignalMessenger.Outbound message)throws Exception;
    @Override void close();
    @FunctionalInterface interface Receiver {SignalMessenger.Outbound receive(String tlsFingerprint,SignalMessenger.Outbound message)throws Exception;}
    @FunctionalInterface interface Authorizer {boolean accepts(String tlsFingerprint)throws Exception;}
    @FunctionalInterface interface Factory {ChatTransport create(Authorizer authorizer,Receiver receiver)throws Exception;}
    static ChatTransport pinned(TlsIdentity identity,NetworkAccess network,InetAddress bind,int port,Authorizer authorizer,Receiver receiver)throws Exception {
        return pinned(identity,network,bind,port,authorizer,receiver,30000);
    }
    /** The absolute lifetime bounds TLS handshakes, trickling reads and blocked writes. */
    static ChatTransport pinned(TlsIdentity identity,NetworkAccess network,InetAddress bind,int port,Authorizer authorizer,Receiver receiver,long deadlineMillis)throws Exception {
        return new PinnedChatTransport(identity,network,bind,port,authorizer,receiver,deadlineMillis);
    }
}

/** A dedicated bounded TLS service; independent of VPN and sharing admission. */
final class PinnedChatTransport implements ChatTransport {
    private final TlsIdentity identity;
    private final NetworkAccess network;
    private final ServerSocket server;
    private final SSLContext serverContext;
    private final long deadlineMillis;
    private final Authorizer authorizer;
    private final Receiver receiver;
    private final Set<Socket> active=ConcurrentHashMap.newKeySet();
    private final Semaphore outgoing=new Semaphore(4);
    private final ScheduledThreadPoolExecutor deadlines=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"chat-socket-deadline");t.setDaemon(true);return t;});
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(0,4,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"chat-tls-receive");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    PinnedChatTransport(TlsIdentity identity,NetworkAccess network,InetAddress bind,int port,Authorizer authorizer,Receiver receiver,long deadlineMillis)throws Exception {
        this.identity=Objects.requireNonNull(identity);this.network=Objects.requireNonNull(network);this.authorizer=Objects.requireNonNull(authorizer);this.receiver=Objects.requireNonNull(receiver);
        if(deadlineMillis<1||deadlineMillis>30000)throw new IllegalArgumentException("Chat deadline must be 1–30000 milliseconds");this.deadlineMillis=deadlineMillis;
        deadlines.setRemoveOnCancelPolicy(true);serverContext=identity.context(null);server=new ServerSocket();
        try{server.setReuseAddress(true);server.bind(new InetSocketAddress(bind,port),8);}
        catch(Exception e){server.close();deadlines.shutdownNow();workers.shutdownNow();throw e;}
        Thread accept=new Thread(this::accept,"chat-tls-listen");accept.setDaemon(true);accept.start();
    }
    public int localPort(){return server.getLocalPort();}
    private void accept(){
        while(!closed)try {
            Socket socket=server.accept();socket.setSoTimeout(30000);socket.setTcpNoDelay(true);active.add(socket);
            if(closed){closeRaw(socket);continue;}
            ScheduledFuture<?> deadline;
            try{deadline=deadline(socket,TimeUnit.MILLISECONDS.toNanos(deadlineMillis));}catch(IOException stopped){closeRaw(socket);continue;}
            try{workers.execute(()->receive(socket,deadline));}catch(RejectedExecutionException full){closeRaw(socket);deadline.cancel(false);}
        }catch(IOException e){if(!closed)close();}
    }
    private void receive(Socket raw,ScheduledFuture<?> deadline){
        try(SSLSocket socket=(SSLSocket)serverContext.getSocketFactory().createSocket(raw,raw.getInetAddress().getHostAddress(),raw.getPort(),true)){
            socket.setUseClientMode(false);socket.setNeedClientAuth(true);socket.setSoTimeout(30000);socket.setEnabledProtocols(protocols(socket.getSupportedProtocols()));
            socket.startHandshake();String fingerprint=TlsIdentity.fingerprint(socket.getSession().getPeerCertificates()[0]);
            // No frame, Signal decryption or contact mutation before explicit TLS authorization.
            if(!authorizer.accepts(fingerprint))throw new SSLPeerUnverifiedException("Unknown chat identity");
            SignalMessenger.Outbound response=receiver.receive(fingerprint,ChatWire.read(socket.getInputStream()));
            if(response!=null)ChatWire.write(socket.getOutputStream(),response);
        }catch(Exception ignored){/* Fail closed, without plaintext, ciphertext or identity logs. */}
        finally{closeRaw(raw);deadline.cancel(false);}
    }
    public SignalMessenger.Outbound exchange(ChatInvite destination,SignalMessenger.Outbound message)throws Exception {
        if(closed)throw new IOException("Chat service is closed");if(!outgoing.tryAcquire())throw new IOException("Chat connection limit");
        Socket raw=null;SSLSocket tls=null;ScheduledFuture<?> deadline=null;long expires=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
        try {
            raw=network.open(destination.host(),destination.port());active.add(raw);raw.setSoTimeout(30000);
            if(closed)throw new IOException("Chat service is closed");
            deadline=deadline(raw,expires-System.nanoTime());
            tls=(SSLSocket)identity.context(destination.tlsFingerprint()).getSocketFactory().createSocket(raw,destination.host(),destination.port(),true);
            tls.setSoTimeout(30000);tls.setEnabledProtocols(protocols(tls.getSupportedProtocols()));tls.startHandshake();
            ChatWire.write(tls.getOutputStream(),message);return ChatWire.read(tls.getInputStream());
        }finally{
            // Close the raw transport first: TLS close_notify must never wait on a blocked writer.
            if(raw!=null)closeRaw(raw);if(tls!=null)try{tls.close();}catch(IOException ignored){}
            if(deadline!=null)deadline.cancel(false);outgoing.release();
        }
    }
    private ScheduledFuture<?> deadline(Socket raw,long remainingNanos)throws IOException {
        if(remainingNanos<=0){closeRaw(raw);throw new SocketTimeoutException("Chat connection deadline");}
        try{return deadlines.schedule(()->closeRaw(raw),remainingNanos,TimeUnit.NANOSECONDS);}
        catch(RejectedExecutionException stopped){closeRaw(raw);throw new IOException("Chat service is closed",stopped);}
    }
    private static String[] protocols(String[] supported){List<String> result=new ArrayList<>();for(String protocol:supported)if(protocol.equals("TLSv1.3")||protocol.equals("TLSv1.2"))result.add(protocol);return result.toArray(new String[0]);}
    private void closeRaw(Socket socket){active.remove(socket);try{socket.close();}catch(IOException ignored){}}
    public void close(){closed=true;try{server.close();}catch(IOException ignored){}for(Socket socket:active)closeRaw(socket);workers.shutdownNow();deadlines.shutdownNow();}
}
