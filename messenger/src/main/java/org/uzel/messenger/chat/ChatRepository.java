package org.uzel.messenger.chat;

import org.uzel.messenger.SignalMessenger;
import org.uzel.messenger.store.TransactionalStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Participant history and retry queue in the same encrypted transaction as the ratchet. */
public final class ChatRepository {
    public record Contact(String id,String name,String inviteCode){}
    /** Optional file metadata carried by a message; the bytes live in an encrypted blob. */
    public record Attachment(String name,String mime,int size){}
    public record Message(String id,String contactId,String text,boolean outgoing,String status,long timeMillis,Attachment attachment){
        public Message(String id,String contactId,String text,boolean outgoing,String status,long timeMillis){this(id,contactId,text,outgoing,status,timeMillis,null);}
    }
    public record Pending(String contactId,String messageId,int cipherType,byte[] ciphertext){
        public Pending{ciphertext=ciphertext.clone();}
        @Override public byte[] ciphertext(){return ciphertext.clone();}
    }
    private final TransactionalStore store;
    public ChatRepository(TransactionalStore store){this.store=Objects.requireNonNull(store);}
    public List<Contact> contacts()throws Exception{return store.read(r->{var result=new ArrayList<Contact>();for(byte[] b:r.list("chat-contact").values())result.add(contactBytes(b));result.sort(Comparator.comparing(Contact::name).thenComparing(Contact::id));return List.copyOf(result);});}
    public Contact contact(String id)throws Exception{return store.read(r->{byte[] b=r.get("chat-contact",id);return b==null?null:contactBytes(b);});}
    public void addContact(TransactionalStore.Records r,Contact c)throws Exception{
        validId(c.id());if(c.name()==null||c.name().isBlank()||c.name().length()>100||c.inviteCode()==null||c.inviteCode().length()>32768)throw new IllegalArgumentException("Invalid contact");
        byte[] old=r.get("chat-contact",c.id());
        if(old!=null&&!contactBytes(old).inviteCode().equals(c.inviteCode()))throw new SecurityException("Contact replacement requires explicit verification");
        if(old==null&&r.list("chat-contact").size()>=256)throw new IllegalStateException("Contact limit reached");
        r.put("chat-contact",c.id(),encode(out->{out.writeUTF(c.id());out.writeUTF(c.name());out.writeUTF(c.inviteCode());}));
    }
    public List<Message> history(String contactId)throws Exception{return store.read(r->{var result=new ArrayList<Message>();for(byte[] b:r.list("chat-message").values()){Message m=messageBytes(b);if(m.contactId().equals(contactId))result.add(m);}result.sort(Comparator.comparingLong(Message::timeMillis).thenComparing(Message::id));return List.copyOf(result.subList(Math.max(0,result.size()-500),result.size()));});}
    /** Removes a contact with its messages, pending frames and stored blobs. */
    public void deleteContact(TransactionalStore.Records r,String contactId)throws Exception{
        validId(contactId);
        if(r.get("chat-contact",contactId)==null)return;
        r.remove("chat-contact",contactId);
        String incoming=contactId+":in:",outgoing=contactId+":out:";
        for(String key:new ArrayList<>(r.list("chat-message").keySet())){
            if(!key.startsWith(incoming)&&!key.startsWith(outgoing))continue;
            byte[] stored=r.get("chat-message",key);
            if(stored!=null)r.remove("chat-blob",messageBytes(stored).id());
            r.remove("chat-message",key);
        }
        for(String key:new ArrayList<>(r.list("chat-pending").keySet()))if(key.startsWith(incoming)||key.startsWith(outgoing))r.remove("chat-pending",key);
    }    public List<Pending> pending()throws Exception{return store.read(r->{var result=new ArrayList<Pending>();for(byte[] b:r.list("chat-pending").values())result.add(pendingBytes(b));result.sort(Comparator.comparing(Pending::messageId));return List.copyOf(result);});}
    public void queueOutgoing(TransactionalStore.Records r,Message m,SignalMessenger.Outbound outbound)throws Exception{
        validate(m);if(!m.outgoing()||!"queued".equals(m.status()))throw new IllegalArgumentException("Invalid outgoing state");
        requireContact(r,m.contactId());String key=key(m.contactId(),m.id(),true);byte[] old=r.get("chat-message",key);
        if(old!=null){Message saved=messageBytes(old);if(!saved.text().equals(m.text()))throw new SecurityException("Message id reused");if("delivered".equals(saved.status()))return;}
        if(old==null&&r.list("chat-pending").size()>=128)throw new IllegalStateException("Outgoing queue is full");
        if(old==null)r.put("chat-message",key,encodeMessage(m));
        Pending p=new Pending(m.contactId(),m.id(),outbound.type(),outbound.body());
        r.put("chat-pending",key,encode(out->{out.writeUTF(p.contactId());out.writeUTF(p.messageId());out.writeInt(p.cipherType());out.writeInt(p.ciphertext.length);out.write(p.ciphertext);}));
    }
    public void receiveText(TransactionalStore.Records r,Message m)throws Exception{
        validate(m);if(m.outgoing())throw new IllegalArgumentException("Incoming expected");requireContact(r,m.contactId());String key=key(m.contactId(),m.id(),false);byte[] old=r.get("chat-message",key);
        if(old!=null){if(!messageBytes(old).text().equals(m.text()))throw new SecurityException("Message id reused");return;}
        r.put("chat-message",key,encodeMessage(m));
    }
    public void delivered(TransactionalStore.Records r,String contactId,String messageId)throws Exception{
        if(messageId!=null&&messageId.startsWith("f_")){
            String pendingKey=key(contactId,messageId,true);
            if(r.get("chat-pending",pendingKey)==null)throw new SecurityException("Unknown acknowledgement");
            r.remove("chat-pending",pendingKey);
            String[] parts=messageId.split("_");
            if(parts.length>1&&!hasPending(r,contactId,"f_"+parts[1]+"_"))markDelivered(r,contactId,parts[1]);
            return;
        }
        markDelivered(r,contactId,messageId);
    }
    private void markDelivered(TransactionalStore.Records r,String contactId,String messageId)throws Exception{
        String key=key(contactId,messageId,true);byte[] old=r.get("chat-message",key);if(old==null)throw new SecurityException("Unknown acknowledgement");
        Message m=messageBytes(old);
        r.put("chat-message",key,encodeMessage(new Message(m.id(),m.contactId(),m.text(),true,"delivered",m.timeMillis(),m.attachment())));r.remove("chat-pending",key);
    }
    private static boolean hasPending(TransactionalStore.Records r,String contact,String prefix)throws Exception{
        String marker=contact+":out:"+prefix;
        for(String key:r.list("chat-pending").keySet())if(key.startsWith(marker))return true;
        return false;
    }
    /** Stores an outgoing file message, its encrypted blob and the metadata frame queue entry. */
    public void queueFile(TransactionalStore.Records r,Message m,byte[] blob,SignalMessenger.Outbound meta)throws Exception{
        validate(m);if(!m.outgoing()||m.attachment()==null||!"queued".equals(m.status()))throw new IllegalArgumentException("Invalid outgoing file");
        if(blob==null||blob.length!=m.attachment().size())throw new IllegalArgumentException("Invalid file content");
        requireContact(r,m.contactId());String key=key(m.contactId(),m.id(),true);
        if(r.get("chat-message",key)!=null)throw new SecurityException("Message id reused");
        r.put("chat-message",key,encodeMessage(m));r.put("chat-blob",m.id(),blob);
        queuePending(r,m.contactId(),"f_"+m.id()+"_meta",meta);
    }
    public void queueChunk(TransactionalStore.Records r,String contactId,String operationId,SignalMessenger.Outbound outbound)throws Exception{
        requireContact(r,contactId);queuePending(r,contactId,operationId,outbound);
    }
    private void queuePending(TransactionalStore.Records r,String contactId,String operationId,SignalMessenger.Outbound outbound)throws Exception{
        if(r.list("chat-pending").size()>=640)throw new IllegalStateException("Outgoing queue is full");
        Pending p=new Pending(contactId,operationId,outbound.type(),outbound.body());
        r.put("chat-pending",key(contactId,operationId,true),encode(out->{out.writeUTF(p.contactId());out.writeUTF(p.messageId());out.writeInt(p.cipherType());out.writeInt(p.ciphertext.length);out.write(p.ciphertext);}));
    }
    public void receiveFile(TransactionalStore.Records r,Message m,byte[] blob)throws Exception{
        validate(m);if(m.outgoing()||m.attachment()==null)throw new IllegalArgumentException("Incoming file expected");
        if(blob==null||blob.length!=m.attachment().size())throw new IllegalArgumentException("Invalid file content");
        requireContact(r,m.contactId());String key=key(m.contactId(),m.id(),false);
        if(r.get("chat-message",key)!=null)return;
        r.put("chat-message",key,encodeMessage(m));r.put("chat-blob",m.id(),blob);
    }
    public byte[] blob(String messageId)throws Exception{return store.read(r->r.get("chat-blob",messageId));}
    private static void requireContact(TransactionalStore.Records r,String id)throws Exception{if(r.get("chat-contact",id)==null)throw new SecurityException("Unknown contact");}
    private static void validId(String id){if(id==null||!id.matches("[A-Za-z0-9_:-]{1,160}"))throw new IllegalArgumentException("Invalid id");}
    private static String key(String contact,String id,boolean outgoing){validId(contact);validId(id);return contact+":"+(outgoing?"out:":"in:")+id;}
    private static void validate(Message m){
        validId(m.id());validId(m.contactId());if(m.timeMillis()<0||m.text()==null)throw new IllegalArgumentException("Invalid message");
        if(m.text().getBytes(StandardCharsets.UTF_8).length>32768)throw new IllegalArgumentException("Invalid message");
        if(m.text().isBlank()&&m.attachment()==null)throw new IllegalArgumentException("Invalid message");
        Attachment a=m.attachment();
        if(a!=null&&(a.name()==null||a.name().isBlank()||a.name().length()>200||a.mime()==null
                ||!a.mime().matches("[a-z0-9.+-]{1,100}/[a-z0-9.+-]{1,100}")||a.size()<1||a.size()>ChatWire.MAX_FILE))
            throw new IllegalArgumentException("Invalid attachment");
    }
    @FunctionalInterface private interface Encoder{void write(DataOutputStream out)throws Exception;}
    private static byte[] encode(Encoder encoder)throws Exception{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(1);encoder.write(out);}return bytes.toByteArray();}
    private static DataInputStream input(byte[] b)throws IOException{var in=new DataInputStream(new ByteArrayInputStream(b));if(in.readInt()!=1)throw new IOException("Unknown chat record version");return in;}
    private static void end(DataInputStream in)throws IOException{if(in.available()!=0)throw new IOException("Trailing chat record");}
    private static Contact contactBytes(byte[] b)throws IOException{try(var in=input(b)){var c=new Contact(in.readUTF(),in.readUTF(),in.readUTF());end(in);return c;}}
    private static byte[] encodeMessage(Message m)throws Exception{
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeInt(2);out.writeUTF(m.id());out.writeUTF(m.contactId());
            byte[] text=m.text().getBytes(StandardCharsets.UTF_8);out.writeInt(text.length);out.write(text);
            out.writeBoolean(m.outgoing());out.writeUTF(m.status());out.writeLong(m.timeMillis());
            Attachment a=m.attachment();out.writeBoolean(a!=null);
            if(a!=null){out.writeUTF(a.name());out.writeUTF(a.mime());out.writeInt(a.size());}
        }
        return bytes.toByteArray();
    }
    private static Message messageBytes(byte[] b)throws IOException{
        try(var in=new DataInputStream(new ByteArrayInputStream(b))){
            int version=in.readInt();
            if(version!=1&&version!=2)throw new IOException("Unknown chat record version");
            String id=in.readUTF(),contact=in.readUTF();int length=in.readInt();
            if(length<0||length>32768||length>in.available())throw new IOException("Invalid message text length");
            byte[] text=new byte[length];in.readFully(text);
            boolean outgoing=in.readBoolean();String status=in.readUTF();long time=in.readLong();
            Attachment attachment=null;
            if(version==2&&in.readBoolean())attachment=new Attachment(in.readUTF(),in.readUTF(),in.readInt());
            end(in);
            return new Message(id,contact,new String(text,StandardCharsets.UTF_8),outgoing,status,time,attachment);
        }
    }
    private static Pending pendingBytes(byte[] b)throws IOException{try(var in=input(b)){String contact=in.readUTF(),id=in.readUTF();int type=in.readInt(),n=in.readInt();if(n<1||n>131072||n!=in.available())throw new IOException("Invalid pending record");byte[] cipher=new byte[n];in.readFully(cipher);return new Pending(contact,id,type,cipher);}}
}
