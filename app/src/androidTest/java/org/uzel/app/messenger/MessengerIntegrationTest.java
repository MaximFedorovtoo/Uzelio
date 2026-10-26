package org.uzel.app.messenger;

import android.test.InstrumentationTestCase;
import org.uzel.app.UzelioApplication;
import org.uzel.messenger.MessengerRuntime;
import org.uzel.messenger.SignalMessenger;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public final class MessengerIntegrationTest extends InstrumentationTestCase {
    public void testApplicationOwnsProtectedMessenger()throws Exception{
        var app=(UzelioApplication)getInstrumentation().getTargetContext().getApplicationContext();
        var runtime=app.messenger().get(30,TimeUnit.SECONDS);
        assertNotNull(runtime.messenger().identity());
        assertSame(runtime,app.messenger().get());
        Path root=app.getNoBackupFilesDir().toPath().resolve("messenger");
        assertTrue(Files.exists(root.resolve("messages.db")));assertTrue(Files.exists(root.resolve("commit.anchor")));
        Path expected=app.getNoBackupFilesDir().toPath().resolve("test-public-messenger-identity");
        byte[] identity=runtime.messenger().identity().serialize();
        if(Files.exists(expected))assertTrue(java.util.Arrays.equals(Files.readAllBytes(expected),identity));
        else Files.write(expected,identity);
    }
    public void testRealSignalAndEncryptedStoreAcrossReopen()throws Exception{
        Path root=Files.createTempDirectory(getInstrumentation().getTargetContext().getNoBackupFilesDir().toPath(),"integrated-");
        Path a=root.resolve("alice"),b=root.resolve("bob");
        var protector=new AndroidKeyProtector();
        SignalMessenger.Outbound sent;byte[] plain="android encrypted atomic message".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(var alice=MessengerRuntime.open(a,protector,(p,k)->new AndroidMessageStore(p.toFile(),k));var bob=MessengerRuntime.open(b,protector,(p,k)->new AndroidMessageStore(p.toFile(),k))){
            var am=alice.messenger();var bm=bob.messenger();am.trustPeer(bm.localAddress(),bm.identity());bm.trustPeer(am.localAddress(),am.identity());
            am.establish(bm.localAddress(),bm.publishPreKeyBundle());sent=am.send(bm.localAddress(),"android-1",plain);
            assertTrue(java.util.Arrays.equals(plain,bm.receive(am.localAddress(),sent.type(),sent.body()).plaintext()));
            assertTrue(bm.receive(am.localAddress(),sent.type(),sent.body()).duplicate());
        }
        try(var alice=MessengerRuntime.open(a,protector,(p,k)->new AndroidMessageStore(p.toFile(),k));var bob=MessengerRuntime.open(b,protector,(p,k)->new AndroidMessageStore(p.toFile(),k))){
            var am=alice.messenger();var bm=bob.messenger();
            assertTrue(java.util.Arrays.equals(sent.body(),am.send(bm.localAddress(),"android-1",plain).body()));
            var reply=bm.send(am.localAddress(),"reply",plain);
            assertTrue(java.util.Arrays.equals(plain,am.receive(bm.localAddress(),reply.type(),reply.body()).plaintext()));
        }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }
}
