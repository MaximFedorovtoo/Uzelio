package org.uzel.messenger.chat;

import org.uzel.core.*;
import org.uzel.messenger.*;
import org.uzel.messenger.relay.RelayClient;
import org.uzel.messenger.relay.RelayEndpoint;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Application-owned peer chat. Only each participant's encrypted repository persists messages. */
public final class ChatService implements AutoCloseable {
    private final SignalMessenger messenger;
    private final ChatRepository repository;
    private final TlsIdentity identity;
    private final ChatTransport transport;
    private final NetworkAccess network;
    private final Map<String,RelayClient> relays=new ConcurrentHashMap<>();
    private final Map<String,Assembly> assemblies=new ConcurrentHashMap<>();
    private final Map<String,ChatInvite> invites=new ConcurrentHashMap<>();
    public interface Listener {
        void incoming(String contactId,ChatRepository.Message message);
        /** Call signaling; default keeps message-only listeners valid. */
        default void call(String contactId,int kind,String callId,String sdp){}
        /** A peer offered its node invitation, so this device can join the same network. */
        default void node(String contactId,String invitation){}
    }
    private static final Listener QUIET=new Listener(){
        public void incoming(String contactId,ChatRepository.Message message){}
    };
    private volatile Listener listener=QUIET;
    /** UI notification hook; a failing listener never changes delivery or storage. */
    public void listener(Listener value){listener=value==null?QUIET:value;}
    private final ScheduledExecutorService retries=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"chat-outbox");t.setDaemon(true);return t;});
    private final AtomicBoolean queuedRetry=new AtomicBoolean();
    private final Set<String> activeContacts=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor deliveries=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128),r->{Thread t=new Thread(r,"chat-delivery");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    public ChatService(MessengerRuntime runtime,TlsIdentity identity,NetworkAccess network,InetAddress bind,int port)throws Exception {
        this(runtime,identity,(authorizer,receiver)->ChatTransport.pinned(identity,network,bind,port,authorizer,receiver),network);
    }
    /** Extension point for end-to-end transports; authorization and Signal processing remain here. */
    public ChatService(MessengerRuntime runtime,TlsIdentity identity,ChatTransport.Factory factory)throws Exception {
        this(runtime,identity,factory,new NetworkAccess());
    }
    public ChatService(MessengerRuntime runtime,TlsIdentity identity,ChatTransport.Factory factory,NetworkAccess network)throws Exception {
        this.network=network==null?new NetworkAccess():network;
        messenger=Objects.requireNonNull(runtime).messenger();repository=runtime.chatRepository();this.identity=Objects.requireNonNull(identity);
        for(var contact:repository.contacts())invites.put(contact.id(),ChatInvite.decode(contact.inviteCode()));
        transport=Objects.requireNonNull(factory).create(this::knownFingerprint,this::receive);
        retries.scheduleWithFixedDelay(this::retryQuietly,2,5,TimeUnit.SECONDS);
        // A contact may already advertise a relay: register our inbox before the first send.
        for(var invite:invites.values())try{relay(invite);}catch(Exception ignored){/* Direct delivery still works. */}
    }
    public int localPort(){return transport.localPort();}
    /** Each call allocates fresh one-time Signal prekeys: create a separate offer for each contact. */
    public String invitation(String advertisedHost)throws Exception {return invitation(advertisedHost,"");}
    /** An invitation that also advertises a storage-less relay for cross-network delivery. */
    public String invitation(String advertisedHost,String relay)throws Exception {
        ensureOpen();
        String code=ChatInvite.create(messenger,advertisedHost,localPort(),identity.fingerprint,relay).encode();
        RelayEndpoint endpoint=ChatInvite.decode(code).relay();
        if(endpoint!=null)relay(endpoint);
        return code;
    }
    public List<ChatRepository.Contact> contacts()throws Exception {ensureOpen();return repository.contacts();}
    /** Deletes a conversation: contact, history, queued frames and stored files. */
    public void deleteContact(String contactId)throws Exception{
        ensureOpen();var contact=requireContact(contactId);ChatInvite invite=invites.get(contact.id());
        messenger.mutate(records->repository.deleteContact(records,contactId));
        invites.remove(contactId);
        if(invite!=null&&invite.relay()!=null){
            String key=invite.relay().encode();
            boolean shared=false;
            for(var other:invites.values())if(other.relay()!=null&&key.equals(other.relay().encode())){shared=true;break;}
            if(!shared){RelayClient client=relays.remove(key);if(client!=null)try{client.close();}catch(Exception ignored){/* Already closed. */}}
        }
    }    public String contactName(String contactId){try{var contact=repository.contact(contactId);return contact==null?"Uzelio":contact.name();}catch(Exception unavailable){return "Uzelio";}}
    public List<ChatRepository.Message> history(String contactId)throws Exception {ensureOpen();requireContact(contactId);return repository.history(contactId);}
    /** Decrypted attachment bytes for a stored file message, or null when it has none. */
    public byte[] attachment(String messageId)throws Exception {ensureOpen();return repository.blob(messageId);}
    /** Import only after comparing the safety code through an authenticated out-of-band channel. */
    public synchronized ChatRepository.Contact addContact(String code,String name)throws Exception {
        ensureOpen();ChatInvite invite=ChatInvite.decode(code==null?null:code.trim());
        long now=System.currentTimeMillis();if(invite.issuedMillis()>now+300000||invite.issuedMillis()<now-7L*86400000)throw new IllegalArgumentException("Chat invitation expired");
        if(invite.address().equals(messenger.localAddress())||TlsIdentity.constantEquals(invite.tlsFingerprint(),identity.fingerprint))throw new IllegalArgumentException("Cannot add this device as a contact");
        if(name==null||name.trim().isEmpty()||name.trim().length()>100)throw new IllegalArgumentException("Contact name is required (up to 100 characters)");
        for(var other:invites.values())if(!other.id().equals(invite.id())&&TlsIdentity.constantEquals(other.tlsFingerprint(),invite.tlsFingerprint()))throw new SecurityException("TLS identity already belongs to another chat contact");
        var contact=new ChatRepository.Contact(invite.id(),name.trim(),invite.encode());
        messenger.trustPeer(invite.address(),invite.bundle().getIdentityKey(),records->repository.addContact(records,contact));
        invites.put(invite.id(),invite);
        messenger.establishIfAbsent(invite.address(),invite.bundle());
        if(invite.relay()!=null)relay(invite);
        retrySoon();return repository.contact(invite.id());
    }
    /**
     * Sends a file as one metadata frame plus bounded chunks. Every frame is a separate
     * Signal event with its own durable outbox row, so a retry never duplicates content.
     */
    public ChatRepository.Message sendFile(String contactId,String name,String mime,byte[] data)throws Exception {
        ensureOpen();var contact=requireContact(contactId);ChatInvite invite=invites.get(contact.id());
        if(name==null||name.isBlank()||name.length()>200)throw new IllegalArgumentException("File name is required");
        if(mime==null||!mime.matches("[a-z0-9.+-]{1,100}/[a-z0-9.+-]{1,100}"))throw new IllegalArgumentException("File type is required");
        if(data==null||data.length<1||data.length>ChatWire.MAX_FILE)throw new IllegalArgumentException("File must be 1..8388608 bytes");
        String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        var attachment=new ChatRepository.Attachment(name,mime,data.length);
        var message=new ChatRepository.Message(id,contactId,"",true,"queued",now,attachment);
        messenger.establishIfAbsent(invite.address(),invite.bundle());
        int chunks=(data.length+ChatWire.MAX_CHUNK-1)/ChatWire.MAX_CHUNK;
        byte[] meta=ChatWire.encodeMeta(new ChatWire.FileMeta(id,name,mime,data.length,chunks,now));
        messenger.send(invite.address(),"f_"+id+"_meta",meta,(records,outbound)->repository.queueFile(records,message,data,outbound));
        for(int index=0;index<chunks;index++){
            int offset=index*ChatWire.MAX_CHUNK,length=Math.min(ChatWire.MAX_CHUNK,data.length-offset);
            byte[] frame=ChatWire.encodeChunk(new ChatWire.FileChunk(id,index,java.util.Arrays.copyOfRange(data,offset,offset+length)));
            String operation="f_"+id+"_"+index;
            messenger.send(invite.address(),operation,frame,(records,outbound)->repository.queueChunk(records,contactId,operation,outbound));
        }
        retrySoon();return message;
    }
    /** Best-effort call signaling: never stored in history and never replayed after a restart. */
    public void sendCall(String contactId,int kind,String callId,String sdp)throws Exception {
        ensureOpen();var contact=requireContact(contactId);ChatInvite invite=invites.get(contact.id());
        byte[] frame=ChatWire.encodeCall(new ChatWire.CallFrame(kind,callId,sdp));
        messenger.establishIfAbsent(invite.address(),invite.bundle());
        var outbound=messenger.send(invite.address(),"call_"+UUID.randomUUID().toString().replace("-",""),frame,(records,ignored)->{});
        try{
            deliveries.execute(()->{
                // A call may start before the P2P session is ready: repeat the frame a few times
                // instead of dropping the offer on the first failed attempt.
                for(int attempt=0;attempt<8;attempt++){
                    try{transport.exchange(invite,outbound);return;}
                    catch(Exception direct){
                        try{RelayClient fallback=relay(invite);if(fallback!=null){fallback.send(invite.address().getName(),outbound);return;}}
                        catch(Exception ignored){/* Try again. */}
                    }
                    try{Thread.sleep(1500);}
                    catch(InterruptedException stopped){Thread.currentThread().interrupt();return;}
                }
            });
        }catch(RejectedExecutionException full){/* Signaling is best effort. */}
    }
    /** Sends this node's invitation through the chat; best effort, never stored in history. */
    public void sendNode(String contactId,String invitation)throws Exception {
        ensureOpen();var contact=requireContact(contactId);ChatInvite invite=invites.get(contact.id());
        byte[] frame=ChatWire.encodeNode(invitation);
        messenger.establishIfAbsent(invite.address(),invite.bundle());
        var outbound=messenger.send(invite.address(),"node_"+UUID.randomUUID().toString().replace("-",""),frame,(records,ignored)->{});
        try{
            deliveries.execute(()->{
                try{transport.exchange(invite,outbound);}
                catch(Exception direct){
                    try{RelayClient fallback=relay(invite);if(fallback!=null)fallback.send(invite.address().getName(),outbound);}
                    catch(Exception ignored){/* Best effort. */}
                }
            });
        }catch(RejectedExecutionException full){/* Best effort. */}
    }    public ChatRepository.Message send(String contactId,String text)throws Exception {
        ensureOpen();var contact=requireContact(contactId);ChatInvite invite=invites.get(contact.id());
        if(text==null||text.trim().isEmpty()||text.getBytes(StandardCharsets.UTF_8).length>ChatWire.MAX_TEXT)throw new IllegalArgumentException("Message must contain 1–32768 UTF-8 bytes");
        String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();var message=new ChatRepository.Message(id,contactId,text,true,"queued",now);
        messenger.establishIfAbsent(invite.address(),invite.bundle());
        byte[] plaintext=ChatWire.encode(new ChatWire.Frame(ChatWire.TEXT,id,now,text));
        messenger.send(invite.address(),id,plaintext,(records,outbound)->repository.queueOutgoing(records,message,outbound));
        retrySoon();return message;
    }
    /** Schedules bounded parallel attempts; delivery requires an authenticated Signal ACK. */
    public void retryNow()throws Exception {
        ensureOpen();Map<String,List<ChatRepository.Pending>> groups=new LinkedHashMap<>();
        for(var pending:repository.pending())groups.computeIfAbsent(pending.contactId(),key->new ArrayList<>()).add(pending);
        for(var entry:groups.entrySet())if(!closed&&activeContacts.add(entry.getKey())) {
            try{deliveries.execute(()->{try{deliver(entry.getKey(),entry.getValue());}finally{activeContacts.remove(entry.getKey());}});}
            catch(RejectedExecutionException full){activeContacts.remove(entry.getKey());}
        }
    }
    private void deliver(String contactId,List<ChatRepository.Pending> pending) {
        try {
            ChatInvite invite=invites.get(requireContact(contactId).id());
            // Metadata must precede chunks; the rest stays in stable order for a deterministic retry.
            var ordered=new java.util.ArrayList<>(pending);
            ordered.sort(java.util.Comparator.comparingInt((ChatRepository.Pending p)->p.messageId().endsWith("_meta")?0:1)
                    .thenComparing(ChatRepository.Pending::messageId));
            // One offline peer occupies at most one worker and gets one failed attempt per sweep.
            for(var message:ordered) {
                if(closed)return;
                var outbound=new SignalMessenger.Outbound(message.cipherType(),message.ciphertext());
                SignalMessenger.Outbound reply;
                try {
                    reply=transport.exchange(invite,outbound);
                } catch(Exception direct) {
                    // Cross-network fallback: the relay forwards the same ciphertext and the peer
                    // answers with its authenticated ACK over the same relay.
                    RelayClient fallback=relay(invite);
                    if(fallback==null)throw direct;
                    fallback.send(invite.address().getName(),outbound);
                    continue;
                }
                receiveFrom(invite,reply,true);
            }
        }catch(Exception ignored){/* Failure leaves original ciphertext queued, without delivery claims or logs. */}
    }
    /** Registers this device on a relay advertised by an invitation; null when none is advertised. */
    private RelayClient relay(ChatInvite invite) {
        RelayEndpoint endpoint=invite.relay();
        return endpoint==null?null:relay(endpoint);
    }
    private RelayClient relay(RelayEndpoint endpoint) {
        return relays.computeIfAbsent(endpoint.encode(),key->{
            RelayClient client=new RelayClient(endpoint,messenger.localAddress().getName(),identity,this::receiveViaRelay,network);
            client.start();
            return client;
        });
    }
    /** A frame arrived from the relay: authorize by the contact's advertised Signal name. */
    private SignalMessenger.Outbound receiveViaRelay(String fromName,SignalMessenger.Outbound wire)throws Exception {
        ensureOpen();
        for(var invite:invites.values())if(invite.address().getName().equals(fromName))return receiveFrom(invite,wire,false);
        throw new SecurityException("Unknown relay sender");
    }
    private boolean knownFingerprint(String fingerprint)throws Exception {
        if(closed)return false;for(var invite:invites.values())if(TlsIdentity.constantEquals(invite.tlsFingerprint(),fingerprint))return true;return false;
    }
    private SignalMessenger.Outbound receive(String fingerprint,SignalMessenger.Outbound wire)throws Exception {
        ensureOpen();
        for(var invite:invites.values())if(TlsIdentity.constantEquals(invite.tlsFingerprint(),fingerprint))return receiveFrom(invite,wire,false);
        throw new SecurityException("Unknown chat identity");
    }
    private SignalMessenger.Outbound receiveFrom(ChatInvite invite,SignalMessenger.Outbound wire,boolean acknowledgementOnly)throws Exception {
        ensureOpen();
        final ChatWire.Frame[] frame=new ChatWire.Frame[1];
        final String[] ackId=new String[1];
        final boolean[] fresh=new boolean[1];
        messenger.receive(invite.address(),wire.type(),wire.body(),(records,received)->{
            fresh[0]=!received.duplicate();
            byte[] plain=received.plaintext();
            int magic=ChatWire.magic(plain);
            if(magic==ChatWire.META_MAGIC){
                ChatWire.FileMeta meta=ChatWire.decodeMeta(plain);ackId[0]="f_"+meta.id()+"_meta";
                if(acknowledgementOnly)throw new SecurityException("Expected an authenticated receipt");
                if(fresh[0]){
                    if(assemblies.size()>16)assemblies.clear();
                    assemblies.computeIfAbsent(key(invite.id(),meta.id()),k->new Assembly()).meta(meta);
                }
            }else if(magic==ChatWire.CHUNK_MAGIC){
                ChatWire.FileChunk chunk=ChatWire.decodeChunk(plain);ackId[0]="f_"+chunk.id()+"_"+chunk.index();
                if(acknowledgementOnly)throw new SecurityException("Expected an authenticated receipt");
                if(fresh[0]){
                    Assembly assembly=assemblies.get(key(invite.id(),chunk.id()));
                    if(assembly==null)throw new IOException("File chunk before metadata");
                    byte[] complete=assembly.chunk(chunk);
                    if(complete!=null){
                        repository.receiveFile(records,new ChatRepository.Message(chunk.id(),invite.id(),"",false,"received",assembly.time(),assembly.attachment()),complete);
                        assemblies.remove(key(invite.id(),chunk.id()));
                        try{listener.incoming(invite.id(),new ChatRepository.Message(chunk.id(),invite.id(),"",false,"received",assembly.time(),assembly.attachment()));}catch(RuntimeException ignored){/* notification only */}
                    }
                }
            }else if(magic==ChatWire.NODE_MAGIC){
                String code=ChatWire.decodeNode(plain);ackId[0]="node_"+Integer.toHexString(code.hashCode());
                if(acknowledgementOnly)throw new SecurityException("Expected an authenticated receipt");
                if(fresh[0])try{listener.node(invite.id(),code);}catch(RuntimeException ignored){/* UI/network only */}            }else if(magic==ChatWire.CALL_MAGIC){
                ChatWire.CallFrame call=ChatWire.decodeCall(plain);ackId[0]="call_"+call.callId()+"_"+call.kind();
                if(acknowledgementOnly)throw new SecurityException("Expected an authenticated receipt");
                if(fresh[0])try{listener.call(invite.id(),call.kind(),call.callId(),call.sdp());}catch(RuntimeException ignored){/* UI only */}
            }else{
                ChatWire.Frame decoded=ChatWire.decode(plain);frame[0]=decoded;ackId[0]=decoded.id();
                if(acknowledgementOnly&&decoded.kind()!=ChatWire.ACK)throw new SecurityException("Expected an authenticated receipt");
                if(decoded.kind()==ChatWire.TEXT)repository.receiveText(records,new ChatRepository.Message(decoded.id(),invite.id(),decoded.text(),false,"received",decoded.timeMillis()));
                else {repository.delivered(records,invite.id(),decoded.id());messenger.discardOutbox(records,invite.address(),decoded.id());}
            }
        });
        if(frame[0]!=null&&frame[0].kind()==ChatWire.TEXT&&fresh[0])notifyIncoming(invite,frame[0]);
        if(ackId[0]==null||(frame[0]!=null&&frame[0].kind()==ChatWire.ACK))return null;
        // The stable operation ID reuses exactly the same encrypted ACK after packet loss/restart.
        String operation="ack_"+Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(ackId[0].getBytes(StandardCharsets.US_ASCII)));
        return messenger.send(invite.address(),operation,ChatWire.encode(new ChatWire.Frame(ChatWire.ACK,ackId[0],0,"")));
    }
    private static String key(String contactId,String fileId){return contactId+":"+fileId;}
    /** Bounded in-memory reassembly; chunks may arrive out of order and reach the store only once. */
    private static final class Assembly {
        private ChatWire.FileMeta meta;
        private final Map<Integer,byte[]> chunks=new java.util.TreeMap<>();
        private int bytes;
        synchronized void meta(ChatWire.FileMeta value){if(meta==null)meta=value;}
        synchronized byte[] chunk(ChatWire.FileChunk chunk)throws IOException{
            if(meta==null)throw new IOException("File chunk before metadata");
            if(chunk.index()>=meta.chunks())throw new IOException("Unexpected file chunk index");
            if(chunks.putIfAbsent(chunk.index(),chunk.data())!=null)return null;
            bytes+=chunk.data().length;
            if(bytes>meta.size())throw new IOException("File exceeds its declared size");
            if(chunks.size()!=meta.chunks())return null;
            java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream(meta.size());
            for(int index=0;index<meta.chunks();index++){
                byte[] part=chunks.get(index);
                if(part==null)throw new IOException("Missing file chunk");
                data.write(part,0,part.length);
            }
            if(data.size()!=meta.size())throw new IOException("File size mismatch");
            return data.toByteArray();
        }
        ChatRepository.Attachment attachment(){return new ChatRepository.Attachment(meta.name(),meta.mime(),meta.size());}
        long time(){return meta.timeMillis();}
    }
    private void notifyIncoming(ChatInvite invite,ChatWire.Frame frame){
        try{listener.incoming(invite.id(),new ChatRepository.Message(frame.id(),invite.id(),frame.text(),false,"received",frame.timeMillis()));}
        catch(RuntimeException ignored){/* A notification must never affect delivery or storage. */}
    }
    private ChatRepository.Contact requireContact(String id)throws Exception {var contact=repository.contact(id);if(contact==null)throw new IllegalArgumentException("Unknown chat contact");return contact;}
    private void retrySoon(){if(!closed&&queuedRetry.compareAndSet(false,true))try{retries.execute(()->{try{retryQuietly();}finally{queuedRetry.set(false);}});}catch(RejectedExecutionException stopped){queuedRetry.set(false);}}
    private void retryQuietly(){if(!closed)try{retryNow();}catch(Exception ignored){/* Failure leaves original ciphertext queued, without delivery claims or logs. */}}
    private void ensureOpen(){if(closed)throw new IllegalStateException("Chat service is closed");}
    public void close(){closed=true;for(RelayClient client:relays.values())client.close();relays.clear();transport.close();retries.shutdownNow();deliveries.shutdownNow();try{retries.awaitTermination(2,TimeUnit.SECONDS);deliveries.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
