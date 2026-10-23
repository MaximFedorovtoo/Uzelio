package org.uzel.core;

import java.io.*;
import java.util.*;

/** Bounded soft-state gossip cache. It stores signed records, never decrypted signals. */
public final class DiscoveryStore {
    public static final int MAX_FRAME=131072;
    private static final int V1=0x555a4431,V2=0x555a4432,MAX_CONTACTS=8;
    // A contact including its signed backup bundle may occupy 32 KiB. Leave the rest for discovery.
    private static final int CONTACT_BUDGET=FederationContact.MAX_BYTES+5;
    private final Map<String,DiscoveryPresence> presences=new LinkedHashMap<>();
    private final Map<String,DiscoverySignal> signals=new LinkedHashMap<>();
    private final Map<String,FederationContact> contacts=new LinkedHashMap<>();
    private void expire(long now){presences.values().removeIf(p->p.expiresAt<=now);signals.values().removeIf(s->s.expiresAt<=now);contacts.values().removeIf(c->c.expiresAt<=now);}
    public synchronized boolean accept(DiscoveryPresence input,long now)throws IOException {
        DiscoveryPresence record=DiscoveryPresence.parse(input.encode(),now);expire(now);DiscoveryPresence old=presences.get(record.fingerprint);
        if(old!=null && record.issuedAt<=old.issuedAt || old==null && presences.size()>=256)return false;
        presences.put(record.fingerprint,record);return true;
    }
    public synchronized boolean accept(DiscoverySignal input,long now)throws IOException {
        DiscoverySignal signal=DiscoverySignal.parse(input.encode(),now);expire(now);String id=signal.senderFingerprint+":"+signal.id;
        if(signals.containsKey(id) || signals.size()>=128)return false;
        long owner=signals.values().stream().filter(s->s.senderFingerprint.equals(signal.senderFingerprint)).count();
        if(owner>=8)return false;signals.put(id,signal);return true;
    }
    /** Remove locally cancelled offers without deleting another sender's cached signals. */
    public synchronized void removeSignals(String senderFingerprint,Collection<String> ids) {
        for(String id:ids)signals.remove(senderFingerprint+":"+id);
    }
    public synchronized boolean accept(FederationContact input,long now)throws IOException {
        FederationContact contact=FederationContact.parseBytes(input.bytes(),now,true);expire(now);
        return retainContact(contact);
    }
    private boolean retainContact(FederationContact contact) {
        FederationContact old=contacts.get(contact.fingerprint);boolean changed=false;
        if((old==null && contacts.size()<MAX_CONTACTS) || (old!=null && contact.issuedAt>old.issuedAt)) {
            contacts.put(contact.fingerprint,contact);changed=true;
        }
        // Each backup has its own signature. Retain it separately so it survives parent expiry.
        for(FederationContact backup:contact.backups)changed=retainContact(backup)||changed;
        return changed;
    }
    public synchronized List<FederationContact> contacts(long now){expire(now);return Collections.unmodifiableList(new ArrayList<>(contacts.values()));}
    public synchronized List<DiscoveryPresence> presences(long now){expire(now);return Collections.unmodifiableList(new ArrayList<>(presences.values()));}
    public synchronized List<DiscoverySignal> signalsFor(String fingerprint,long now){expire(now);List<DiscoverySignal> result=new ArrayList<>();for(DiscoverySignal s:signals.values())if(s.recipientFingerprint.equals(fingerprint))result.add(s);return Collections.unmodifiableList(result);}
    public synchronized List<DiscoverySignal> signals(long now){expire(now);return Collections.unmodifiableList(new ArrayList<>(signals.values()));}
    public synchronized void clear(){presences.clear();signals.clear();contacts.clear();}
    /** Legacy v1 frame; callers opt into contact gossip explicitly. */
    public synchronized byte[] exportFrame(long now)throws IOException {return exportFrame(now,false);}
    public synchronized byte[] exportFrameWithContacts(long now)throws IOException {return exportFrame(now,true);}
    private byte[] exportFrame(long now,boolean withContacts)throws IOException {
        expire(now);List<byte[]> records=new ArrayList<>();
        // Random samples prevent permanent starvation of a tail larger than one frame.
        List<DiscoveryPresence> ps=new ArrayList<>(presences.values());Collections.shuffle(ps);
        List<DiscoverySignal> ss=new ArrayList<>(signals.values());Collections.shuffle(ss);
        int size=8;
        if(withContacts) {
            List<FederationContact> cs=new ArrayList<>(contacts.values());Collections.shuffle(cs);int contactSize=0;
            for(FederationContact contact:cs){byte[] encoded=contact.bytes();int cost=5+encoded.length;
                if(contactSize+cost<=CONTACT_BUDGET){records.add(tag(3,encoded));contactSize+=cost;size+=cost;}}
        }
        for(DiscoverySignal signal:ss){byte[] encoded=signal.encode();if(size+5+encoded.length<=MAX_FRAME){records.add(tag(2,encoded));size+=5+encoded.length;}}
        for(DiscoveryPresence presence:ps){byte[] encoded=presence.encode();if(size+5+encoded.length<=MAX_FRAME){records.add(tag(1,encoded));size+=5+encoded.length;}}
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(withContacts?V2:V1);out.writeInt(records.size());for(byte[] record:records)out.write(record);return bytes.toByteArray();
    }
    private static byte[] tag(int kind,byte[] value)throws IOException {ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeByte(kind);DiscoveryWire.field(out,value);return bytes.toByteArray();}
    public synchronized void ingestFrame(byte[] frame,long now)throws IOException {
        if(frame==null || frame.length>MAX_FRAME)throw new IOException("Discovery frame size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(frame));int version=in.readInt();
        if(version!=V1 && version!=V2)throw new IOException("Discovery frame version");
        int count=in.readInt();if(count<0 || count>(version==V2?392:384))throw new IOException("Discovery record count");
        List<Object> verified=new ArrayList<>();int contactCount=0;
        for(int i=0;i<count;i++){int kind=in.readUnsignedByte();
            if(kind!=1 && kind!=2 && (kind!=3 || version!=V2))throw new IOException("Discovery record kind");
            if(kind==3 && ++contactCount>MAX_CONTACTS)throw new IOException("Discovery contact count");
            byte[] value=DiscoveryWire.field(in,kind==1?8192:kind==3?FederationContact.MAX_BYTES:100000);
            if(kind==1)verified.add(DiscoveryPresence.parse(value,now));
            else if(kind==2)verified.add(DiscoverySignal.parse(value,now));
            else verified.add(FederationContact.parseBytes(value,now,true));
        }
        DiscoveryWire.end(in);
        // Nothing is mutated until every record, including every backup signature, verifies.
        for(Object record:verified)if(record instanceof DiscoveryPresence)accept((DiscoveryPresence)record,now);
        else if(record instanceof DiscoverySignal)accept((DiscoverySignal)record,now);
        else {expire(now);retainContact((FederationContact)record);}
    }
    public synchronized byte[] exchange(byte[] frame,long now)throws IOException {
        ingestFrame(frame,now);return exportFrame(now,new DataInputStream(new ByteArrayInputStream(frame)).readInt()==V2);
    }
}
