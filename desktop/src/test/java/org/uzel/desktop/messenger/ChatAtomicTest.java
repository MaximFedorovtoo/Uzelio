package org.uzel.desktop.messenger;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.uzel.messenger.*;
import org.uzel.messenger.chat.ChatRepository;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class ChatAtomicTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void historyAndRetryQueueRollbackTogetherWithRatchet()throws Exception{
        var a=new SignalProtocolAddress(UUID.randomUUID().toString(),1);var b=new SignalProtocolAddress(UUID.randomUUID().toString(),1);
        try(var as=new JdbcMessageStore(tmp.newFolder().toPath().resolve("db"),new byte[32]);var bs=new JdbcMessageStore(tmp.newFolder().toPath().resolve("db"),new byte[32])){
            var alice=new SignalMessenger(as,a);var bob=new SignalMessenger(bs,b);alice.initializeIdentity();bob.initializeIdentity();
            var ar=new ChatRepository(as);var br=new ChatRepository(bs);
            alice.trustPeer(b,bob.identity(),r->ar.addContact(r,new ChatRepository.Contact("bob","Bob","test-invite")));
            bob.trustPeer(a,alice.identity(),r->br.addContact(r,new ChatRepository.Contact("alice","Alice","test-invite")));
            alice.establishIfAbsent(b,bob.publishPreKeyBundle());
            var outgoing=new ChatRepository.Message("one","bob","secret",true,"queued",1);byte[] payload={1,2,3};
            var before=SignalPersistenceTest.snapshot(as);
            assertThrows(IOException.class,()->alice.send(b,"one",payload,(r,packet)->{ar.queueOutgoing(r,outgoing,packet);throw new IOException("after history");}));
            assertEquals(before,SignalPersistenceTest.snapshot(as));assertTrue(ar.history("bob").isEmpty());assertTrue(ar.pending().isEmpty());
            var sent=alice.send(b,"one",payload,(r,packet)->ar.queueOutgoing(r,outgoing,packet));
            assertArrayEquals(sent.body(),ar.pending().get(0).ciphertext());
            var incoming=new ChatRepository.Message("one","alice","secret",false,"received",1);
            before=SignalPersistenceTest.snapshot(bs);
            assertThrows(IOException.class,()->bob.receive(a,sent.type(),sent.body(),(r,m)->{br.receiveText(r,incoming);throw new IOException("after inbox");}));
            assertEquals(before,SignalPersistenceTest.snapshot(bs));assertTrue(br.history("alice").isEmpty());
            bob.receive(a,sent.type(),sent.body(),(r,m)->br.receiveText(r,incoming));
            bob.receive(a,sent.type(),sent.body(),(r,m)->br.receiveText(r,incoming));assertEquals(1,br.history("alice").size());
            var ack=bob.send(a,"ack_one",new byte[]{4});before=SignalPersistenceTest.snapshot(as);
            assertThrows(IOException.class,()->alice.receive(b,ack.type(),ack.body(),(r,m)->{ar.delivered(r,"bob","one");alice.discardOutbox(r,b,"one");throw new IOException("after delivery");}));
            assertEquals(before,SignalPersistenceTest.snapshot(as));assertEquals("queued",ar.history("bob").get(0).status());assertEquals(1,ar.pending().size());
            alice.receive(b,ack.type(),ack.body(),(r,m)->{ar.delivered(r,"bob","one");alice.discardOutbox(r,b,"one");});
            assertEquals("delivered",ar.history("bob").get(0).status());assertTrue(ar.pending().isEmpty());
            // A second contact import must not reset an established ratchet.
            before=SignalPersistenceTest.snapshot(as);alice.establishIfAbsent(b,bob.publishPreKeyBundle());assertEquals(before,SignalPersistenceTest.snapshot(as));
        }
    }
}
