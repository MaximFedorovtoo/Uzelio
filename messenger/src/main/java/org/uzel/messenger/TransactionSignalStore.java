package org.uzel.messenger;

import org.uzel.messenger.store.TransactionalStore.Records;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.state.*;
import org.signal.libsignal.protocol.groups.state.*;
import java.util.*;
import java.nio.ByteBuffer;

/** Fresh adapter per transaction. No mutable libsignal object outlives its callback. */
final class TransactionSignalStore implements SignalProtocolStore {
    private final Records records;
    TransactionSignalStore(Records records){this.records=records;}
    static String address(SignalProtocolAddress a){
        String name=a.getName();
        if(!UUID.fromString(name).toString().equals(name)||a.getDeviceId()<1)throw new IllegalArgumentException("Invalid device address");
        return name+":"+a.getDeviceId();
    }
    interface Checked<T>{T run()throws Exception;}
    static <T>T checked(Checked<T> action){try{return action.run();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("Signal record operation failed",e);}}
    private byte[] get(String s,String k){return checked(()->records.get(s,k));}
    private void put(String s,String k,byte[] v){checked(()->{records.put(s,k,v);return null;});}
    private void remove(String s,String k){checked(()->{records.remove(s,k);return null;});}
    private Map<String,byte[]> list(String s){return checked(()->records.list(s));}
    @Override public IdentityKeyPair getIdentityKeyPair(){return checked(()->new IdentityKeyPair(Objects.requireNonNull(get("local","identity"),"Missing identity")));}
    @Override public int getLocalRegistrationId(){return ByteBuffer.wrap(Objects.requireNonNull(get("local","registration"))).getInt();}
    @Override public IdentityChange saveIdentity(SignalProtocolAddress a,IdentityKey key){
        byte[] pinned=get("trust",address(a));
        if(pinned==null||!Arrays.equals(pinned,key.serialize()))throw new SecurityException("Identity has not been explicitly trusted");
        return IdentityChange.NEW_OR_UNCHANGED;
    }
    @Override public boolean isTrustedIdentity(SignalProtocolAddress a,IdentityKey key,Direction d){return Arrays.equals(get("trust",address(a)),key.serialize());}
    @Override public IdentityKey getIdentity(SignalProtocolAddress a){byte[] b=get("trust",address(a));return b==null?null:checked(()->new IdentityKey(b));}
    @Override public SessionRecord loadSession(SignalProtocolAddress a){byte[] b=get("session",address(a));return b==null?new SessionRecord():checked(()->new SessionRecord(b));}
    @Override public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses)throws NoSessionException{
        List<SessionRecord> result=new ArrayList<>();for(var a:addresses){if(!containsSession(a))throw new NoSessionException("Missing stored session");result.add(loadSession(a));}return result;
    }
    @Override public List<Integer> getSubDeviceSessions(String name){List<Integer> ids=new ArrayList<>();for(String key:list("session").keySet())if(key.startsWith(name+":")){int id=Integer.parseInt(key.substring(name.length()+1));if(id!=1)ids.add(id);}return ids;}
    @Override public void storeSession(SignalProtocolAddress a,SessionRecord s){put("session",address(a),s.serialize());}
    @Override public boolean containsSession(SignalProtocolAddress a){return get("session",address(a))!=null;}
    @Override public void deleteSession(SignalProtocolAddress a){remove("session",address(a));}
    @Override public void deleteAllSessions(String name){for(String k:list("session").keySet())if(k.startsWith(name+":"))remove("session",k);}
    private byte[] required(String space,int id)throws InvalidKeyIdException{byte[] b=get(space,Integer.toString(id));if(b==null)throw new InvalidKeyIdException("Missing key "+id);return b;}
    @Override public PreKeyRecord loadPreKey(int id)throws InvalidKeyIdException{byte[] b=required("prekey",id);return checked(()->new PreKeyRecord(b));}
    @Override public void storePreKey(int id,PreKeyRecord r){put("prekey",""+id,r.serialize());}
    @Override public boolean containsPreKey(int id){return get("prekey",""+id)!=null;}
    @Override public void removePreKey(int id){remove("prekey",""+id);}
    @Override public SignedPreKeyRecord loadSignedPreKey(int id)throws InvalidKeyIdException{byte[] b=required("signed",id);return checked(()->new SignedPreKeyRecord(b));}
    @Override public List<SignedPreKeyRecord> loadSignedPreKeys(){List<SignedPreKeyRecord> result=new ArrayList<>();for(byte[] b:list("signed").values())result.add(checked(()->new SignedPreKeyRecord(b)));return result;}
    @Override public void storeSignedPreKey(int id,SignedPreKeyRecord r){put("signed",""+id,r.serialize());}
    @Override public boolean containsSignedPreKey(int id){return get("signed",""+id)!=null;}
    @Override public void removeSignedPreKey(int id){remove("signed",""+id);}
    @Override public KyberPreKeyRecord loadKyberPreKey(int id)throws InvalidKeyIdException{byte[] b=required("kyber",id);return checked(()->new KyberPreKeyRecord(b));}
    @Override public List<KyberPreKeyRecord> loadKyberPreKeys(){List<KyberPreKeyRecord> result=new ArrayList<>();for(byte[] b:list("kyber").values())result.add(checked(()->new KyberPreKeyRecord(b)));return result;}
    @Override public void storeKyberPreKey(int id,KyberPreKeyRecord r){put("kyber",""+id,r.serialize());}
    @Override public boolean containsKyberPreKey(int id){return get("kyber",""+id)!=null;}
    @Override public void markKyberPreKeyUsed(int id,int signedId,ECPublicKey base)throws ReusedBaseKeyException{
        // publishPreKeyBundle creates one-time PQ keys, never last-resort keys.
        if(!containsKyberPreKey(id))throw new ReusedBaseKeyException("Consumed one-time PQ key");
        remove("kyber",""+id);
    }
    @Override public void storeSenderKey(SignalProtocolAddress a,UUID id,SenderKeyRecord record){throw new UnsupportedOperationException("Group sessions are not enabled");}
    @Override public SenderKeyRecord loadSenderKey(SignalProtocolAddress a,UUID id){throw new UnsupportedOperationException("Group sessions are not enabled");}
}
