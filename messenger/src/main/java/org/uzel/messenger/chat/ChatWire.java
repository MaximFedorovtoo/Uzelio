package org.uzel.messenger.chat;

import org.uzel.messenger.SignalMessenger;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Bounded binary framing. Application frames are always inside Signal ciphertext. */
public final class ChatWire {
    public static final int MAX_WIRE=262144,MAX_TEXT=32768,FRAGMENT=16384;
    public static final int TEXT=1,ACK=2;
    public record Frame(int kind,String id,long timeMillis,String text){}
    private ChatWire(){}
    public static byte[] encode(Frame frame)throws IOException {
        if((frame.kind()!=TEXT&&frame.kind()!=ACK)||!validId(frame.id())||frame.timeMillis()<0)throw new IOException("Invalid message frame");
        byte[] text;
        try{ByteBuffer utf8=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(frame.text()));text=new byte[utf8.remaining()];utf8.get(text);}
        catch(CharacterCodingException e){throw new IOException("Invalid Unicode message",e);}
        if(text.length>MAX_TEXT||(frame.kind()==ACK&&text.length!=0))throw new IOException("Invalid message text");
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(0x55434d31);out.writeByte(frame.kind());out.writeUTF(frame.id());out.writeLong(frame.timeMillis());out.writeInt(text.length);out.write(text);}return bytes.toByteArray();
    }
    public static Frame decode(byte[] bytes)throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(bytes.length>MAX_TEXT+256||in.readInt()!=0x55434d31)throw new IOException("Invalid message frame");
            int kind=in.readUnsignedByte();String id=in.readUTF();long time=in.readLong();int length=in.readInt();
            if((kind!=TEXT&&kind!=ACK)||!validId(id)||time<0||length<0||length>MAX_TEXT||length!=in.available()||(kind==ACK&&length!=0))throw new IOException("Invalid message frame");
            byte[] text=new byte[length];in.readFully(text);
            try{return new Frame(kind,id,time,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(text)).toString());}
            catch(CharacterCodingException e){throw new IOException("Invalid UTF-8 message",e);}
        }
    }
    public static void write(OutputStream stream,SignalMessenger.Outbound outbound)throws IOException {
        byte[] ciphertext=outbound.body();if(ciphertext.length<1||ciphertext.length>131072)throw new IOException("Invalid ciphertext");
        DataOutputStream out=new DataOutputStream(stream);out.writeInt(ciphertext.length+8);out.writeInt(0x55435731);out.writeInt(outbound.type());
        for(int i=0;i<ciphertext.length;i+=FRAGMENT)out.write(ciphertext,i,Math.min(FRAGMENT,ciphertext.length-i));out.flush();
    }
    public static SignalMessenger.Outbound read(InputStream stream)throws IOException {
        DataInputStream in=new DataInputStream(stream);int size=in.readInt();if(size<9||size>MAX_WIRE||size>131080)throw new IOException("Oversized chat frame");
        if(in.readInt()!=0x55435731)throw new IOException("Unknown chat wire version");int type=in.readInt();if(type!=2&&type!=3)throw new IOException("Unsupported ciphertext type");
        byte[] body=new byte[size-8];for(int offset=0;offset<body.length;){int n=in.read(body,offset,Math.min(FRAGMENT,body.length-offset));if(n<0)throw new EOFException();offset+=n;}return new SignalMessenger.Outbound(type,body);
    }
    private static boolean validId(String id){return id!=null&&id.matches("[A-Za-z0-9_-]{1,128}");}
}
