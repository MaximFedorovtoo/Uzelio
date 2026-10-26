package org.uzel.desktop.messenger;

import org.uzel.core.TlsIdentity;
import org.uzel.messenger.SignalMessenger;
import org.uzel.messenger.chat.ChatInvite;
import org.uzel.messenger.chat.ChatTransport;
import org.uzel.messenger.chat.ChatWire;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Direct pinned mTLS first; when no direct address answers, the same pinned mTLS runs
 * inside a P2P stream supplied by the paired traversal transport. The relay stays behind
 * this transport: a null stream leaves the original direct failure untouched.
 */
public final class DesktopChatTransport implements ChatTransport {
    @FunctionalInterface
    public interface ChatStreams { Socket open(String fingerprint)throws IOException; }
    private final TlsIdentity identity;
    private final ChatTransport direct;
    private final ChatStreams streams;
    public DesktopChatTransport(TlsIdentity identity,ChatTransport direct,ChatStreams streams){
        this.identity=identity;this.direct=direct;this.streams=streams;
    }
    public int localPort(){return direct.localPort();}
    public SignalMessenger.Outbound exchange(ChatInvite destination,SignalMessenger.Outbound message)throws Exception {
        try{return direct.exchange(destination,message);}
        catch(Exception directFailure){
            // A null stream means no live P2P session: the direct failure is the honest result.
            Socket stream=streams.open(destination.tlsFingerprint());
            if(stream==null)throw directFailure;
            SSLSocket tls=null;
            try{
                stream.setSoTimeout(30000);
                tls=(SSLSocket)identity.context(destination.tlsFingerprint()).getSocketFactory()
                        .createSocket(stream,destination.host(),destination.port(),true);
                tls.setSoTimeout(30000);tls.setEnabledProtocols(protocols(tls.getSupportedProtocols()));tls.startHandshake();
                ChatWire.write(tls.getOutputStream(),message);return ChatWire.read(tls.getInputStream());
            }finally{
                // Close the carrier first: TLS close_notify must never wait on a blocked writer.
                try{stream.close();}catch(IOException ignored){}
                if(tls!=null)try{tls.close();}catch(IOException ignored){}
            }
        }
    }
    public void close(){direct.close();}
    private static String[] protocols(String[] supported){
        List<String> result=new ArrayList<>();for(String protocol:supported)if(protocol.equals("TLSv1.3")||protocol.equals("TLSv1.2"))result.add(protocol);
        return result.toArray(new String[0]);
    }
}
