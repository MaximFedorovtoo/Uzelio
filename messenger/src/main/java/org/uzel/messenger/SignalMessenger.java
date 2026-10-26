package org.uzel.messenger;

import org.uzel.messenger.store.TransactionalStore;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.ecc.*;
import org.signal.libsignal.protocol.kem.*;
import org.signal.libsignal.protocol.state.*;
import org.signal.libsignal.protocol.message.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Local personal-message persistence. Network/certificate authorization is a separate caller. */
public final class SignalMessenger {
    private final TransactionalStore store;
    private final SignalProtocolAddress local;
    public SignalMessenger(TransactionalStore store,SignalProtocolAddress local){this.store=Objects.requireNonNull(store);TransactionSignalStore.address(local);this.local=local;}
    public SignalProtocolAddress localAddress(){return local;}
    public void initializeIdentity()throws Exception{
        store.write(r->{
            if(r.get("local","identity")!=null){checkAddress(r);return null;}
            if(!r.list("local").isEmpty()||!r.list("session").isEmpty())throw new SecurityException("Incomplete identity requires recovery");
            r.put("local","identity",IdentityKeyPair.generate().serialize());
            r.put("local","registration",ByteBuffer.allocate(4).putInt(new SecureRandom().nextInt(16380)+1).array());
            r.put("local","address",TransactionSignalStore.address(local).getBytes(StandardCharsets.UTF_8));return null;
        });
    }
    private void checkAddress(TransactionalStore.Records r)throws Exception{
        if(!Arrays.equals(r.get("local","address"),TransactionSignalStore.address(local).getBytes(StandardCharsets.UTF_8)))throw new SecurityException("Local device binding mismatch");
    }
    public IdentityKey identity()throws Exception{return store.read(r->{checkAddress(r);return new TransactionSignalStore(r).getIdentityKeyPair().getPublicKey();});}
    @FunctionalInterface public interface Mutation {void apply(TransactionalStore.Records records)throws Exception;}
    @FunctionalInterface public interface SendEffect {void apply(TransactionalStore.Records records,Outbound outbound)throws Exception;}
    @FunctionalInterface public interface ReceiveEffect {void apply(TransactionalStore.Records records,Received received)throws Exception;}
    public byte[] sign(byte[] bytes)throws Exception{byte[] copy=bytes.clone();return store.read(r->{checkAddress(r);return new TransactionSignalStore(r).getIdentityKeyPair().getPrivateKey().calculateSignature(copy);});}
    public boolean hasSession(SignalProtocolAddress peer)throws Exception{return store.read(r->{checkAddress(r);return new TransactionSignalStore(r).containsSession(peer);});}
    /** Caller must verify device ownership/certificate before explicitly pinning this identity. */
    public void trustPeer(SignalProtocolAddress peer,IdentityKey identity)throws Exception{
        trustPeer(peer,identity,r->{});
    }
    public void trustPeer(SignalProtocolAddress peer,IdentityKey identity,Mutation effect)throws Exception{
        String address=TransactionSignalStore.address(peer);byte[] bytes=identity.serialize();
        store.write(r->{checkAddress(r);byte[] old=r.get("trust",address);if(old!=null&&!Arrays.equals(old,bytes))throw new SecurityException("Identity replacement requires explicit recovery");r.put("trust",address,bytes);effect.apply(r);return null;});
    }
    public PreKeyBundle publishPreKeyBundle()throws Exception{
        return store.write(r->{checkAddress(r);var s=new TransactionSignalStore(r);byte[] saved=r.get("local","prekey-counter");int id=saved==null?1:Math.addExact(ByteBuffer.wrap(saved).getInt(),1);
            ECKeyPair pre=ECKeyPair.generate(),signed=ECKeyPair.generate();KEMKeyPair kyber=KEMKeyPair.generate(KEMKeyType.KYBER_1024);
            var identity=s.getIdentityKeyPair();byte[] sig=identity.getPrivateKey().calculateSignature(signed.getPublicKey().serialize()),ksig=identity.getPrivateKey().calculateSignature(kyber.getPublicKey().serialize());
            s.storePreKey(id,new PreKeyRecord(id,pre));s.storeSignedPreKey(id,new SignedPreKeyRecord(id,System.currentTimeMillis(),signed,sig));s.storeKyberPreKey(id,new KyberPreKeyRecord(id,System.currentTimeMillis(),kyber,ksig));
            r.put("local","prekey-counter",ByteBuffer.allocate(4).putInt(id).array());
            return new PreKeyBundle(s.getLocalRegistrationId(),local.getDeviceId(),id,pre.getPublicKey(),id,signed.getPublicKey(),sig,identity.getPublicKey(),id,kyber.getPublicKey(),ksig);
        });
    }
    public void establish(SignalProtocolAddress peer,PreKeyBundle bundle)throws Exception{
        TransactionSignalStore.address(peer);
        store.write(r->{checkAddress(r);var s=new TransactionSignalStore(r);if(!s.isTrustedIdentity(peer,bundle.getIdentityKey(),IdentityKeyStore.Direction.SENDING))throw new SecurityException("Untrusted prekey identity");new SessionBuilder(s,peer,local).process(bundle);return null;});
    }
    public void establishIfAbsent(SignalProtocolAddress peer,PreKeyBundle bundle)throws Exception{
        TransactionSignalStore.address(peer);
        store.write(r->{checkAddress(r);var s=new TransactionSignalStore(r);if(!s.isTrustedIdentity(peer,bundle.getIdentityKey(),IdentityKeyStore.Direction.SENDING))throw new SecurityException("Untrusted prekey identity");if(!s.containsSession(peer))new SessionBuilder(s,peer,local).process(bundle);return null;});
    }
    public record Outbound(int type,byte[] body){public Outbound{body=body.clone();}@Override public byte[] body(){return body.clone();}}
    public record Received(String id,byte[] plaintext,boolean duplicate){public Received{plaintext=plaintext.clone();}@Override public byte[] plaintext(){return plaintext.clone();}}
    public Outbound send(SignalProtocolAddress peer,String operationId,byte[] plaintext)throws Exception{
        return send(peer,operationId,plaintext,(r,o)->{});
    }
    public Outbound send(SignalProtocolAddress peer,String operationId,byte[] plaintext,SendEffect effect)throws Exception{
        if(operationId==null||!operationId.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Invalid operation id");
        if(plaintext==null||plaintext.length>65536)throw new IllegalArgumentException("Message too large");
        byte[] input=plaintext.clone();String key=TransactionSignalStore.address(peer)+":"+operationId;
        return store.write(r->{checkAddress(r);byte[] old=r.get("outbox",key);
            if(old!=null){if(!Arrays.equals(r.get("sent",key),input))throw new IllegalArgumentException("Operation id reused with different content");Outbound out=decode(old);effect.apply(r,out);return out;}
            CiphertextMessage message=new SessionCipher(new TransactionSignalStore(r),local,peer).encrypt(input);
            Outbound out=new Outbound(message.getType(),message.serialize());r.put("outbox",key,encode(out));r.put("sent",key,input);effect.apply(r,out);return out;
        });
    }
    public Received receive(SignalProtocolAddress peer,int type,byte[] ciphertext)throws Exception{
        return receive(peer,type,ciphertext,(r,m)->{});
    }
    public Received receive(SignalProtocolAddress peer,int type,byte[] ciphertext,ReceiveEffect effect)throws Exception{
        if(ciphertext==null||ciphertext.length==0||ciphertext.length>131072)throw new IllegalArgumentException("Invalid ciphertext size");
        if(type!=CiphertextMessage.PREKEY_TYPE&&type!=CiphertextMessage.WHISPER_TYPE)throw new IllegalArgumentException("Unsupported ciphertext type");
        byte[] wire=ciphertext.clone();MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(("uzelio.inbox.v1:"+TransactionSignalStore.address(peer)+":"+type+":").getBytes(StandardCharsets.UTF_8));
        String id=Base64.getUrlEncoder().withoutPadding().encodeToString(hash.digest(wire));
        return store.write(r->{checkAddress(r);byte[] existing=r.get("inbox",id);if(existing!=null){Received received=new Received(id,existing,true);effect.apply(r,received);return received;}
            var cipher=new SessionCipher(new TransactionSignalStore(r),local,peer);
            byte[] plain=type==CiphertextMessage.PREKEY_TYPE?cipher.decrypt(new PreKeySignalMessage(wire)):cipher.decrypt(new SignalMessage(wire));
            if(plain.length>65536)throw new IllegalArgumentException("Decrypted message too large");
            r.put("inbox",id,plain);r.put("receipt",id,"STORED".getBytes(StandardCharsets.US_ASCII));Received received=new Received(id,plain,false);effect.apply(r,received);return received;
        });
    }
    public void discardOutbox(SignalProtocolAddress peer,String operationId)throws Exception{store.write(r->{checkAddress(r);discardOutbox(r,peer,operationId);return null;});}
    public void discardOutbox(TransactionalStore.Records r,SignalProtocolAddress peer,String operationId)throws Exception{r.remove("outbox",TransactionSignalStore.address(peer)+":"+operationId);}
    private static byte[] encode(Outbound o)throws IOException{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(o.type());out.writeInt(o.body.length);out.write(o.body);}return bytes.toByteArray();}
    private static Outbound decode(byte[] bytes)throws IOException{try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){int type=in.readInt(),size=in.readInt();if(size<1||size>131072||size!=in.available())throw new IOException("Invalid stored outbox");byte[] body=new byte[size];in.readFully(body);return new Outbound(type,body);}}
}
