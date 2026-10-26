package org.uzel.messenger.chat;

import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.uzel.messenger.SignalMessenger;
import org.uzel.messenger.relay.RelayEndpoint;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** An explicit out-of-band contact offer, signed by the advertised Signal identity. */
public final class ChatInvite {
    public static final String PREFIX="uzelio-chat:1:";
    public static final int MAX_CODE=16384, V1=0x55434831, V2=0x55434832;
    private final SignalProtocolAddress address;
    private final String host,pin,encoded;
    private final int port;
    private final long issued;
    private final PreKeyBundle bundle;
    private final RelayEndpoint relay;
    private ChatInvite(SignalProtocolAddress address,String host,int port,String pin,long issued,PreKeyBundle bundle,String encoded,RelayEndpoint relay){this.address=address;this.host=host;this.port=port;this.pin=pin;this.issued=issued;this.bundle=bundle;this.encoded=encoded;this.relay=relay;}
    public SignalProtocolAddress address(){return address;}
    public String id(){return address.getName()+":"+address.getDeviceId();}
    public String host(){return host;}
    public int port(){return port;}
    public String tlsFingerprint(){return pin;}
    public PreKeyBundle bundle(){return bundle;}
    public String encode(){return encoded;}
    public long issuedMillis(){return issued;}
    /** Optional storage-less relay advertised by the inviter; null for direct-only invitations. */
    public RelayEndpoint relay(){return relay;}
    /** Compare this code through a separately authenticated conversation. */
    public String safetyCode()throws GeneralSecurityException {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");hash.update(bundle.getIdentityKey().serialize());hash.update(pin.getBytes(StandardCharsets.US_ASCII));
        byte[] bytes=hash.digest();StringBuilder result=new StringBuilder();for(int i=0;i<12;i++){if(i>0&&i%4==0)result.append(' ');result.append(String.format(Locale.ROOT,"%02x",bytes[i]&255));}return result.toString();
    }
    public static ChatInvite create(SignalMessenger messenger,String host,int port,String pin)throws Exception {
        return create(messenger,host,port,pin,"");
    }
    /** v2 invitations advertise an optional relay; v1 stays readable for existing contacts. */
    public static ChatInvite create(SignalMessenger messenger,String host,int port,String pin,String relay)throws Exception {
        validate(messenger.localAddress(),host,port,pin);
        String relayValue=relay==null?"":relay.trim();
        if(!RelayEndpoint.isValid(relayValue))throw new IllegalArgumentException("Invalid relay endpoint");
        PreKeyBundle bundle=messenger.publishPreKeyBundle();var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(relayValue.isEmpty()?V1:V2);out.writeUTF(messenger.localAddress().getName());out.writeInt(messenger.localAddress().getDeviceId());
            out.writeUTF(host);out.writeInt(port);out.writeUTF(pin);out.writeLong(System.currentTimeMillis());
            if(!relayValue.isEmpty())out.writeUTF(relayValue);
            out.writeInt(bundle.getRegistrationId());out.writeInt(bundle.getPreKeyId());write(out,bundle.getPreKey().serialize());
            out.writeInt(bundle.getSignedPreKeyId());write(out,bundle.getSignedPreKey().serialize());write(out,bundle.getSignedPreKeySignature());
            write(out,bundle.getIdentityKey().serialize());out.writeInt(bundle.getKyberPreKeyId());write(out,bundle.getKyberPreKey().serialize());write(out,bundle.getKyberPreKeySignature());
        }
        byte[] payload=bytes.toByteArray(),signature=messenger.sign(payload);bytes.reset();
        try(var out=new DataOutputStream(bytes)){write(out,payload);write(out,signature);}
        return decode(PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray()));
    }
    public static ChatInvite decode(String code)throws Exception {
        if(code==null||code.length()>MAX_CODE||!code.startsWith(PREFIX))throw new IllegalArgumentException("Invalid chat invitation");
        byte[] all=Base64.getUrlDecoder().decode(code.substring(PREFIX.length()));byte[] payload,signature;
        try(var in=new DataInputStream(new ByteArrayInputStream(all))){payload=read(in,8192);signature=read(in,128);end(in);}
        try(var in=new DataInputStream(new ByteArrayInputStream(payload))) {
            int version=in.readInt();
            if(version!=V1&&version!=V2)throw new IOException("Unknown chat invitation version");
            var address=new SignalProtocolAddress(in.readUTF(),in.readInt());String host=in.readUTF();int port=in.readInt();String pin=in.readUTF();long issued=in.readLong();
            RelayEndpoint relay=version==V2?RelayEndpoint.parse(in.readUTF()):null;
            validate(address,host,port,pin);
            int registration=in.readInt(),preId=in.readInt();var pre=new ECPublicKey(read(in,64));int signedId=in.readInt();var signed=new ECPublicKey(read(in,64));byte[] signedSig=read(in,128);
            var identity=new IdentityKey(read(in,64));int kyberId=in.readInt();var kyber=new KEMPublicKey(read(in,2048));byte[] kyberSig=read(in,128);end(in);
            if(registration<1||registration>16380||preId<1||signedId<1||kyberId<1||signature.length!=64||!identity.getPublicKey().verifySignature(payload,signature))throw new SecurityException("Invalid chat invitation signature");
            if(!identity.getPublicKey().verifySignature(signed.serialize(),signedSig)||!identity.getPublicKey().verifySignature(kyber.serialize(),kyberSig))throw new SecurityException("Invalid chat prekey signature");
            var bundle=new PreKeyBundle(registration,address.getDeviceId(),preId,pre,signedId,signed,signedSig,identity,kyberId,kyber,kyberSig);
            return new ChatInvite(address,host,port,pin,issued,bundle,code,relay);
        }
    }
    private static void validate(SignalProtocolAddress a,String host,int port,String pin) {
        if(a.getName()==null||!a.getName().matches("[A-Za-z0-9_-]{1,128}")||a.getDeviceId()<1||a.getDeviceId()>127)throw new IllegalArgumentException("Invalid chat address");
        if(host==null||host.length()<1||host.length()>253||!host.matches("[A-Za-z0-9_.:%-]+")||port<1||port>65535)throw new IllegalArgumentException("Invalid chat endpoint");
        if(pin==null||!pin.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid chat certificate pin");
    }
    private static void write(DataOutputStream out,byte[] bytes)throws IOException {out.writeInt(bytes.length);out.write(bytes);}
    private static byte[] read(DataInputStream in,int max)throws IOException {int length=in.readInt();if(length<1||length>max||length>in.available())throw new IOException("Invalid invitation field");byte[] bytes=new byte[length];in.readFully(bytes);return bytes;}
    private static void end(DataInputStream in)throws IOException {if(in.available()!=0)throw new IOException("Trailing invitation data");}
}
