package org.uzel.messenger.chat;

import org.uzel.messenger.SignalMessenger;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Bounded binary framing. Application frames are always inside Signal ciphertext. */
public final class ChatWire {
    public static final int MAX_WIRE=262144,MAX_TEXT=32768,FRAGMENT=16384;
    public static final int TEXT=1,ACK=2,FILE_META=3,FILE_CHUNK=4;
    public static final int MAX_FILE=8*1024*1024,MAX_CHUNK=32768,MAX_CHUNKS=256;
    public static final int META_MAGIC=0x55434d32,CHUNK_MAGIC=0x55434d33;
    /** Frame discriminator; 0 for a payload too short to carry a header. */
    public static int magic(byte[] bytes){
        if(bytes==null||bytes.length<8)return 0;
        return ((bytes[0]&255)<<24)|((bytes[1]&255)<<16)|((bytes[2]&255)<<8)|(bytes[3]&255);
    }
    public record Frame(int kind,String id,long timeMillis,String text){}
    public record FileMeta(String id,String name,String mime,int size,int chunks,long timeMillis){}
    public record FileChunk(String id,int index,byte[] data){public FileChunk{data=data.clone();}@Override public byte[] data(){return data.clone();}}
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
    public static byte[] encodeMeta(FileMeta meta)throws IOException {
        if(!validId(meta.id())||!validName(meta.name())||!validMime(meta.mime()))throw new IOException("Invalid file metadata");
        int chunks=(meta.size()+MAX_CHUNK-1)/MAX_CHUNK;
        if(meta.size()<1||meta.size()>MAX_FILE||chunks<1||chunks>MAX_CHUNKS||meta.chunks()!=chunks||meta.timeMillis()<0)throw new IOException("Invalid file size");
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){out.writeInt(0x55434d32);out.writeByte(FILE_META);out.writeUTF(meta.id());out.writeUTF(meta.name());out.writeUTF(meta.mime());out.writeInt(meta.size());out.writeInt(meta.chunks());out.writeLong(meta.timeMillis());}
        return bytes.toByteArray();
    }
    public static FileMeta decodeMeta(byte[] bytes)throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=0x55434d32||in.readUnsignedByte()!=FILE_META)throw new IOException("Invalid file metadata");
            var meta=new FileMeta(in.readUTF(),in.readUTF(),in.readUTF(),in.readInt(),in.readInt(),in.readLong());
            int chunks=(meta.size()+MAX_CHUNK-1)/MAX_CHUNK;
            if(!validId(meta.id())||!validName(meta.name())||!validMime(meta.mime())||meta.size()<1||meta.size()>MAX_FILE
                    ||meta.chunks()!=chunks||meta.chunks()>MAX_CHUNKS||meta.timeMillis()<0||in.available()!=0)throw new IOException("Invalid file metadata");
            return meta;
        }
    }
    public static byte[] encodeChunk(FileChunk chunk)throws IOException {
        byte[] data=chunk.data();
        if(!validId(chunk.id())||chunk.index()<0||chunk.index()>=MAX_CHUNKS||data.length<1||data.length>MAX_CHUNK)throw new IOException("Invalid file chunk");
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){out.writeInt(0x55434d33);out.writeByte(FILE_CHUNK);out.writeUTF(chunk.id());out.writeInt(chunk.index());out.writeInt(data.length);out.write(data);}
        return bytes.toByteArray();
    }
    public static FileChunk decodeChunk(byte[] bytes)throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=0x55434d33||in.readUnsignedByte()!=FILE_CHUNK)throw new IOException("Invalid file chunk");
            String id=in.readUTF();int index=in.readInt(),length=in.readInt();
            if(!validId(id)||index<0||index>=MAX_CHUNKS||length<1||length>MAX_CHUNK||length!=in.available())throw new IOException("Invalid file chunk");
            byte[] data=new byte[length];in.readFully(data);return new FileChunk(id,index,data);
        }
    }
    private static boolean validName(String name){return name!=null&&!name.isBlank()&&name.length()<=200&&name.indexOf('\n')<0;}
    private static boolean validMime(String mime){return mime!=null&&mime.matches("[a-z0-9.+-]{1,100}/[a-z0-9.+-]{1,100}");}
    private static boolean validId(String id){return id!=null&&id.matches("[A-Za-z0-9_-]{1,128}");}
}
