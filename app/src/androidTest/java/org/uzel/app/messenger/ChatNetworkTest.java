package org.uzel.app.messenger;

import android.test.InstrumentationTestCase;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.uzel.core.*;
import org.uzel.messenger.*;
import org.uzel.messenger.chat.*;
import java.net.*;
import java.io.*;
import javax.net.ssl.SSLSocket;
import java.nio.file.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.math.BigInteger;
import java.util.*;
import javax.security.auth.x500.X500Principal;

/** Two app-isolated Android peers; never touches product profile or key aliases. */
public final class ChatNetworkTest extends InstrumentationTestCase {
    public void testAndroidTlsSignalDeliveryAndParticipantHistory()throws Exception {
        String prefix="org.uzel.messenger.chat-test."+UUID.randomUUID();
        Path root=Files.createTempDirectory(getInstrumentation().getTargetContext().getNoBackupFilesDir().toPath(),"chat-network-test-");
        String[] aliases={prefix+".a.wrap",prefix+".b.wrap",prefix+".a.tls",prefix+".b.tls"};
        try {
            TlsIdentity ai=identity(aliases[2]),bi=identity(aliases[3]);
            GateNetwork gate=new GateNetwork();
            // Android may choose ::1 for getLoopbackAddress(); the offers below advertise IPv4.
            InetAddress loopback=InetAddress.getByName("127.0.0.1");
            try(var a=MessengerRuntime.open(root.resolve("a"),new AndroidKeyProtector(aliases[0]),(p,k)->new AndroidMessageStore(p.toFile(),k));
                var b=MessengerRuntime.open(root.resolve("b"),new AndroidKeyProtector(aliases[1]),(p,k)->new AndroidMessageStore(p.toFile(),k));
                var ac=new ChatService(a,ai,gate,loopback,0);
                var bc=new ChatService(b,bi,new NetworkAccess(),loopback,0)) {
                String aid=ac.addContact(bc.invitation("127.0.0.1"),"Боб").id(),bid=bc.addContact(ac.invitation("127.0.0.1"),"Алиса").id();
                var message=ac.send(aid,"Привет, Android — сеть 🔐");
                var pending=a.chatRepository().pending().get(0);var destination=ChatInvite.decode(a.chatRepository().contact(aid).inviteCode());
                var wire=new SignalMessenger.Outbound(pending.cipherType(),pending.ciphertext());
                byte[] ack=rawExchange(ai,destination,wire).body();
                assertTrue(Arrays.equals(ack,rawExchange(ai,destination,wire).body()));assertEquals(1,bc.history(bid).size());
                assertEquals("queued",ac.history(aid).get(0).status());gate.open=true;ac.retryNow();
                await(()->ac.history(aid).stream().anyMatch(m->m.id().equals(message.id())&&m.status().equals("delivered")));
                assertEquals("Привет, Android — сеть 🔐",bc.history(bid).get(0).text());
                assertFalse(bc.history(bid).get(0).outgoing());assertEquals(1,bc.history(bid).size());
                ac.retryNow();bc.retryNow();assertEquals(1,bc.history(bid).size());assertTrue(a.chatRepository().pending().isEmpty());
                bc.send(bid,"Ответ через взаимно проверенный TLS");
                await(()->ac.history(aid).size()==2);
                assertEquals(2,bc.history(bid).size());
            }
        }finally {
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);for(String alias:aliases)keys.deleteEntry(alias);
            try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(file);}
        }
    }
    private static final class GateNetwork extends NetworkAccess {volatile boolean open;@Override public Socket open(String host,int port)throws IOException {if(!open)throw new IOException("isolated test offline");return super.open(host,port);}}
    private static SignalMessenger.Outbound rawExchange(TlsIdentity identity,ChatInvite destination,SignalMessenger.Outbound message)throws Exception {
        try(SSLSocket socket=(SSLSocket)identity.context(destination.tlsFingerprint()).getSocketFactory().createSocket(destination.host(),destination.port())){socket.setSoTimeout(5000);socket.startHandshake();ChatWire.write(socket.getOutputStream(),message);return ChatWire.read(socket.getInputStream());}
    }
    private static TlsIdentity identity(String alias)throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(new X500Principal("CN=Isolated chat test"))
                .setCertificateSerialNumber(new BigInteger(120,new SecureRandom()).add(BigInteger.ONE))
                .setCertificateNotBefore(new Date(System.currentTimeMillis()-86400000L))
                .setCertificateNotAfter(new Date(System.currentTimeMillis()+86400000L)).build());
        generator.generateKeyPair();KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);return TlsIdentity.fromKeyStore(keys,null,alias);
    }
    private interface Condition {boolean get()throws Exception;}
    private static void await(Condition condition)throws Exception {long deadline=System.nanoTime()+20_000_000_000L;while(!condition.get()){if(System.nanoTime()>deadline)throw new AssertionError("Timed out waiting for Android chat delivery");Thread.sleep(25);}}
}
