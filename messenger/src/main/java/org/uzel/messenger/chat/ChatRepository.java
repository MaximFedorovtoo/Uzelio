package org.uzel.messenger.chat;

import org.uzel.messenger.SignalMessenger;
import org.uzel.messenger.store.TransactionalStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Participant history and retry queue in the same encrypted transaction as the ratchet. */
public final class ChatRepository {
    public record Contact(String id,String name,String inviteCode){}
    public record Message(String id,String contactId,String text,boolean outgoing,String status,long timeMillis){}
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
    public List<Pending> pending()throws Exception{return store.read(r->{var result=new ArrayList<Pending>();for(byte[] b:r.list("chat-pending").values())result.add(pendingBytes(b));result.sort(Comparator.comparing(Pending::messageId));return List.copyOf(result);});}
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
        String key=key(contactId,messageId,true);byte[] old=r.get("chat-message",key);if(old==null)throw new SecurityException("Unknown acknowledgement");
        Message m=messageBytes(old);r.put("chat-message",key,encodeMessage(new Message(m.id(),m.contactId(),m.text(),true,"delivered",m.timeMillis())));r.remove("chat-pending",key);
    }
    private static void requireContact(TransactionalStore.Records r,String id)throws Exception{if(r.get("chat-contact",id)==null)throw new SecurityException("Unknown contact");}
    private static void validId(String id){if(id==null||!id.matches("[A-Za-z0-9_:-]{1,160}"))throw new IllegalArgumentException("Invalid id");}
    private static String key(String contact,String id,boolean outgoing){validId(contact);validId(id);return contact+":"+(outgoing?"out:":"in:")+id;}
    private static void validate(Message m){validId(m.id());validId(m.contactId());if(m.text()==null||m.text().isBlank()||m.text().getBytes(StandardCharsets.UTF_8).length>32768||m.timeMillis()<0)throw new IllegalArgumentException("Invalid message");}
    @FunctionalInterface private interface Encoder{void write(DataOutputStream out)throws Exception;}
    private static byte[] encode(Encoder encoder)throws Exception{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(1);encoder.write(out);}return bytes.toByteArray();}
    private static DataInputStream input(byte[] b)throws IOException{var in=new DataInputStream(new ByteArrayInputStream(b));if(in.readInt()!=1)throw new IOException("Unknown chat record version");return in;}
    private static void end(DataInputStream in)throws IOException{if(in.available()!=0)throw new IOException("Trailing chat record");}
    private static Contact contactBytes(byte[] b)throws IOException{try(var in=input(b)){var c=new Contact(in.readUTF(),in.readUTF(),in.readUTF());end(in);return c;}}
    private static byte[] encodeMessage(Message m)throws Exception{return encode(out->{out.writeUTF(m.id());out.writeUTF(m.contactId());byte[] text=m.text().getBytes(StandardCharsets.UTF_8);out.writeInt(text.length);out.write(text);out.writeBoolean(m.outgoing());out.writeUTF(m.status());out.writeLong(m.timeMillis());});}
    private static Message messageBytes(byte[] b)throws IOException{try(var in=input(b)){String id=in.readUTF(),contact=in.readUTF();int length=in.readInt();if(length<0||length>32768||length>in.available())throw new IOException("Invalid message text length");byte[] text=new byte[length];in.readFully(text);var m=new Message(id,contact,new String(text,StandardCharsets.UTF_8),in.readBoolean(),in.readUTF(),in.readLong());end(in);return m;}}
    private static Pending pendingBytes(byte[] b)throws IOException{try(var in=input(b)){String contact=in.readUTF(),id=in.readUTF();int type=in.readInt(),n=in.readInt();if(n<1||n>131072||n!=in.available())throw new IOException("Invalid pending record");byte[] cipher=new byte[n];in.readFully(cipher);return new Pending(contact,id,type,cipher);}}
}
