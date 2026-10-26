package org.uzel.desktop.messenger;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.uzel.messenger.SignalMessenger;
import org.uzel.messenger.store.TransactionalStore;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class SignalPersistenceTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    final SignalProtocolAddress a = new SignalProtocolAddress("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",1);
    final SignalProtocolAddress b = new SignalProtocolAddress("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",1);
    final byte[] key = new byte[32];
    @Test public void realRatchetMessagesAndExactRetriesSurviveReopen() throws Exception {
        Path ap=tmp.newFolder("a").toPath().resolve("history.db"),bp=tmp.newFolder("b").toPath().resolve("history.db");
        byte[] text="transactional secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SignalMessenger.Outbound sent;
        try(var as=new JdbcMessageStore(ap,key);var bs=new JdbcMessageStore(bp,key)) {
            var alice=new SignalMessenger(as,a);var bob=new SignalMessenger(bs,b);
            alice.initializeIdentity();bob.initializeIdentity();
            alice.trustPeer(b,bob.identity());bob.trustPeer(a,alice.identity());
            alice.establish(b,bob.publishPreKeyBundle());
            sent=alice.send(b,"operation-1",text);
            assertArrayEquals(sent.body(),alice.send(b,"operation-1",text).body());
            assertThrows(IllegalArgumentException.class,()->alice.send(b,"operation-1",new byte[]{1}));
            assertArrayEquals(text,bob.receive(a,sent.type(),sent.body()).plaintext());
            assertTrue(bs.read(r->r.list("prekey")).isEmpty());
            assertTrue(bs.read(r->r.list("kyber")).isEmpty());
            assertTrue(bob.receive(a,sent.type(),sent.body()).duplicate());
        }
        try(var as=new JdbcMessageStore(ap,key);var bs=new JdbcMessageStore(bp,key)) {
            var alice=new SignalMessenger(as,a);var bob=new SignalMessenger(bs,b);
            assertArrayEquals(sent.body(),alice.send(b,"operation-1",text).body());
            var reply=bob.send(a,"reply",text);
            assertArrayEquals(text,alice.receive(b,reply.type(),reply.body()).plaintext());
            var good=alice.send(b,"next",text);byte[] bad=good.body();bad[bad.length-1]^=1;
            assertThrows(Exception.class,()->bob.receive(a,good.type(),bad));
            assertArrayEquals(text,bob.receive(a,good.type(),good.body()).plaintext());
        }
    }
    @Test public void failureAfterSignalCallbacksRollsBackSessionPrekeyAndMessageTogether() throws Exception {
        try(var as=new JdbcMessageStore(tmp.newFolder("a").toPath().resolve("history.db"),key);
            var bs=new JdbcMessageStore(tmp.newFolder("b").toPath().resolve("history.db"),key)) {
            var failing=new FailAfterWork(bs);var alice=new SignalMessenger(as,a);var bob=new SignalMessenger(failing,b);
            alice.initializeIdentity();bob.initializeIdentity();alice.trustPeer(b,bob.identity());bob.trustPeer(a,alice.identity());
            alice.establish(b,bob.publishPreKeyBundle());var packet=alice.send(b,"one",new byte[]{7});
            var before=bs.read(r->r.list("session")); failing.fail=true;
            assertThrows(Exception.class,()->bob.receive(a,packet.type(),packet.body()));
            assertEquals(before.size(),bs.read(r->r.list("session")).size());
            assertTrue(bs.read(r->r.list("inbox")).isEmpty());assertTrue(bs.read(r->r.list("receipt")).isEmpty());
            assertEquals(1,bs.read(r->r.list("prekey")).size());assertEquals(1,bs.read(r->r.list("kyber")).size());
            failing.fail=false;assertArrayEquals(new byte[]{7},bob.receive(a,packet.type(),packet.body()).plaintext());
            var reply=bob.send(a,"reply",new byte[]{8});alice.receive(b,reply.type(),reply.body());
            var next=alice.send(b,"second",new byte[]{9});var snapshot=snapshot(bs);
            failing.fail=true;assertThrows(Exception.class,()->bob.receive(a,next.type(),next.body()));
            assertEquals(snapshot,snapshot(bs));
            failing.fail=false;assertArrayEquals(new byte[]{9},bob.receive(a,next.type(),next.body()).plaintext());
        }
    }
    static final class FailAfterWork implements TransactionalStore {
        final TransactionalStore delegate;boolean fail;
        FailAfterWork(TransactionalStore d){delegate=d;}
        public <T>T read(Work<T>w)throws Exception{return delegate.read(w);}
        public <T>T write(Work<T>w)throws Exception{return delegate.write(r->{T t=w.run(r);if(fail)throw new java.io.IOException("injected after callbacks");return t;});}
        public void close()throws Exception{delegate.close();}
    }
    @Test public void untrustedAndReplacedIdentitiesLeaveAllRecordsUnchanged()throws Exception{
        try(var as=new JdbcMessageStore(tmp.newFolder("a").toPath().resolve("db"),key);var bs=new JdbcMessageStore(tmp.newFolder("b").toPath().resolve("db"),key)){
            var alice=new SignalMessenger(as,a);var bob=new SignalMessenger(bs,b);alice.initializeIdentity();bob.initializeIdentity();
            var bundle=bob.publishPreKeyBundle();var before=snapshot(as);
            assertThrows(SecurityException.class,()->alice.establish(b,bundle));assertEquals(before,snapshot(as));
            alice.trustPeer(b,bob.identity());before=snapshot(as);
            assertThrows(SecurityException.class,()->alice.trustPeer(b,alice.identity()));assertEquals(before,snapshot(as));
            var wrong=alice.publishPreKeyBundle();before=snapshot(as);
            assertThrows(SecurityException.class,()->alice.establish(b,wrong));assertEquals(before,snapshot(as));
            alice.establish(b,bundle);var packet=alice.send(b,"untrusted",new byte[]{5});before=snapshot(bs);
            assertThrows(org.signal.libsignal.protocol.UntrustedIdentityException.class,()->bob.receive(a,packet.type(),packet.body()));assertEquals(before,snapshot(bs));
            bob.trustPeer(a,alice.identity());assertArrayEquals(new byte[]{5},bob.receive(a,packet.type(),packet.body()).plaintext());
        }
    }
    static Map<String,String> snapshot(TransactionalStore store)throws Exception{
        return store.read(r->{Map<String,String> result=new TreeMap<>();
            for(String space:new String[]{"session","prekey","kyber","signed","trust","inbox","receipt","outbox","sent"})
                for(var e:r.list(space).entrySet())result.put(space+":"+e.getKey(),Base64.getEncoder().encodeToString(e.getValue()));
            return result;});
    }
}
