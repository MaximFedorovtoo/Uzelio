package org.uzel.core;

import java.io.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiscoveryContactGossipTest {
    private static final int V1=0x555a4431,V2=0x555a4432;
    private static byte[] frame(int version,int[] kinds,byte[]... values)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(version);out.writeInt(values.length);
        for(int i=0;i<values.length;i++){out.writeByte(kinds[i]);DiscoveryWire.field(out,values[i]);}return bytes.toByteArray();
    }
    private static FederationContact contact(TlsIdentity id,long now)throws Exception {return FederationContact.create(id,"127.0.0.1",9443,now);}
    @Test public void v2ExchangePropagatesVerifiedContactsAndV1RemainsCompatible()throws Exception {
        long now=System.currentTimeMillis();FederationContact contact=contact(FederationTestIdentities.create(),now);
        DiscoveryStore a=new DiscoveryStore(),b=new DiscoveryStore(),c=new DiscoveryStore();
        // Wire input first makes the pre-implementation failure a behavioral assertion.
        try {a.ingestFrame(frame(V2,new int[]{3},contact.bytes()),now);}catch(IOException e){fail("Valid v2 signed contact rejected: "+e.getMessage());}
        assertTrue(a.accept(contact(FederationTestIdentities.create(),now),now));
        b.ingestFrame(a.exchange(b.exportFrameWithContacts(now),now),now);
        c.ingestFrame(b.exchange(c.exportFrameWithContacts(now),now),now);
        assertEquals(2,c.contacts(now).size());
        assertEquals(V1,new DataInputStream(new ByteArrayInputStream(c.exportFrame(now))).readInt());
        DiscoveryStore legacy=new DiscoveryStore();legacy.ingestFrame(c.exchange(legacy.exportFrame(now),now),now);
        assertTrue(legacy.contacts(now).isEmpty());
        assertEquals(V2,new DataInputStream(new ByteArrayInputStream(c.exchange(frame(V2,new int[0]),now))).readInt());
    }
    @Test public void contactFreshnessExpiryFlatteningAndClearAreBounded()throws Exception {
        long now=System.currentTimeMillis();TlsIdentity owner=FederationTestIdentities.create();
        List<FederationContact> backups=new ArrayList<>();for(int i=0;i<7;i++)backups.add(contact(FederationTestIdentities.create(),now));
        DiscoveryStore store=new DiscoveryStore();FederationContact parent=FederationContact.create(owner,"127.0.0.1",9443,now,backups);
        assertTrue(store.accept(parent,now));assertEquals(8,store.contacts(now).size());assertFalse(store.accept(parent,now));
        assertFalse(store.accept(contact(FederationTestIdentities.create(),now),now));
        FederationContact newer=FederationContact.create(owner,"127.0.0.1",9555,now+1);
        assertTrue(store.accept(newer,now+1));assertFalse(store.accept(parent,now+1));
        assertEquals(9555,store.contacts(now+1).stream().filter(c->c.fingerprint.equals(owner.fingerprint)).findFirst().get().port);
        assertTrue(store.contacts(now+1+FederationContact.TTL_MILLIS).isEmpty());
        try{store.accept(parent,parent.expiresAt);fail("expired contact");}catch(IOException expected){}
        store.accept(parent,now);store.clear();assertTrue(store.contacts(now).isEmpty());
    }
    @Test public void tamperedOrExcessContactsRejectWholeFrameBeforeMutation()throws Exception {
        long now=System.currentTimeMillis();TlsIdentity id=FederationTestIdentities.create();FederationContact contact=contact(id,now);
        DiscoveryPresence presence=new DiscoveryIdentity(id).presence("RU",0,100,now);
        byte[] tampered=contact.bytes();tampered[tampered.length-1]^=1;DiscoveryStore store=new DiscoveryStore();
        try{store.ingestFrame(frame(V2,new int[]{1,3},presence.encode(),tampered),now);fail("tamper");}catch(IOException expected){}
        assertTrue(store.presences(now).isEmpty());assertTrue(store.contacts(now).isEmpty());
        int[] kinds=new int[10];Arrays.fill(kinds,3);kinds[0]=1;byte[][] records=new byte[10][];records[0]=presence.encode();for(int i=1;i<10;i++)records[i]=contact.bytes();
        try{store.ingestFrame(frame(V2,kinds,records),now);fail("contact count");}catch(IOException expected){}
        assertTrue(store.presences(now).isEmpty());assertTrue(store.contacts(now).isEmpty());
        try{store.ingestFrame(frame(V1,new int[]{3},contact.bytes()),now);fail("v1 contact");}catch(IOException expected){}
    }
    @Test public void contactsLeaveRoomForPresenceAndSignal()throws Exception {
        long now=System.currentTimeMillis();TlsIdentity id=FederationTestIdentities.create();DiscoveryIdentity alice=new DiscoveryIdentity(id),bob=new DiscoveryIdentity(FederationTestIdentities.create());
        alice.presence("RU",0,100,now);DiscoveryPresence presence=bob.presence("DE",0,100,now);DiscoverySignal signal=alice.seal(presence,PairingCode.offer(id,"v=0",new Invitation("127.0.0.1",9443,id.fingerprint,TlsIdentity.randomSecret()),now).encode(),now);
        DiscoveryStore a=new DiscoveryStore();a.accept(presence,now);a.accept(signal,now);
        for(int i=0;i<8;i++)a.accept(contact(FederationTestIdentities.create(),now),now);
        byte[] exported=a.exportFrameWithContacts(now);assertTrue(exported.length<=DiscoveryStore.MAX_FRAME);
        DiscoveryStore b=new DiscoveryStore();b.ingestFrame(exported,now);
        assertEquals(8,b.contacts(now).size());assertEquals(1,b.presences(now).size());assertEquals(1,b.signals(now).size());
    }
    @Test public void bundledContactsStayWithinReservedBudget()throws Exception {
        long now=System.currentTimeMillis();List<TlsIdentity> ids=new ArrayList<>();List<FederationContact> flat=new ArrayList<>();
        for(int i=0;i<8;i++){TlsIdentity id=FederationTestIdentities.create();ids.add(id);flat.add(FederationContact.create(id,"a".repeat(253),9443,now));}
        DiscoveryStore store=new DiscoveryStore();
        for(int i=0;i<8;i++){List<FederationContact> backups=new ArrayList<>(flat);backups.remove(i);store.accept(FederationContact.create(ids.get(i),"a".repeat(253),9443,now+1,backups),now+1);}
        assertEquals(8,store.contacts(now+1).size());
        DiscoveryPresence presence=new DiscoveryIdentity(ids.get(0)).presence("RU",0,100,now+1);store.accept(presence,now+1);
        byte[] frame=store.exportFrameWithContacts(now+1);DataInputStream in=new DataInputStream(new ByteArrayInputStream(frame));in.readInt();int count=in.readInt(),contactBytes=0;
        for(int i=0;i<count;i++){int kind=in.readUnsignedByte();byte[] value=DiscoveryWire.field(in,FederationContact.MAX_BYTES);if(kind==3)contactBytes+=5+value.length;}
        assertTrue(contactBytes<=FederationContact.MAX_BYTES+5);assertTrue(contactBytes>0);
        DiscoveryStore target=new DiscoveryStore();target.ingestFrame(frame,now+1);assertEquals(1,target.presences(now+1).size());assertTrue(target.contacts(now+1).size()<=8);
    }
    @Test public void cancellingExactOwnedSignalFreesQuotaAndPreservesOtherMessages()throws Exception {
        long now=System.currentTimeMillis();TlsIdentity aliceKey=FederationTestIdentities.create(),bobKey=FederationTestIdentities.create();
        DiscoveryIdentity alice=new DiscoveryIdentity(aliceKey),bob=new DiscoveryIdentity(bobKey);
        DiscoveryPresence alicePresence=alice.presence("RU",0,100,now),bobPresence=bob.presence("DE",0,100,now);
        String offer=PairingCode.offer(aliceKey,"v=0",new Invitation("127.0.0.1",9443,aliceKey.fingerprint,TlsIdentity.randomSecret()),now).encode();
        DiscoveryStore store=new DiscoveryStore();List<DiscoverySignal> owned=new ArrayList<>();
        for(int i=0;i<8;i++){DiscoverySignal signal=alice.seal(i==7?alicePresence:bobPresence,offer,now);owned.add(signal);assertTrue(store.accept(signal,now));}
        DiscoverySignal next=alice.seal(bobPresence,offer,now);assertFalse(store.accept(next,now));
        DiscoverySignal unrelated=DiscoverySignal.create(bobKey,DiscoverySignal.header(bobPresence,aliceKey.fingerprint,owned.get(0).id,now,now+1000,new byte[32],new byte[12]),new byte[16],now);
        assertTrue(store.accept(unrelated,now));
        store.removeSignals(aliceKey.fingerprint,Arrays.asList(owned.get(0).id,"missing"));
        assertEquals(8,store.signals(now).size());assertTrue(store.signals(now).stream().anyMatch(s->s.senderFingerprint.equals(bobKey.fingerprint)&&s.id.equals(unrelated.id)));
        assertTrue(store.signals(now).stream().anyMatch(s->s.id.equals(owned.get(7).id)));assertTrue(store.accept(next,now));
    }}
