package org.uzel.messenger;

import org.uzel.messenger.keys.*;
import org.uzel.messenger.persistence.AnchoredStore;
import org.uzel.messenger.store.TransactionalStore;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Application-owned local messenger. Holds the profile lock before inspecting initialization state. */
public final class MessengerRuntime implements AutoCloseable {
    @FunctionalInterface public interface StoreFactory {TransactionalStore open(Path db,byte[] key)throws Exception;}
    private final FileChannel channel;
    private final FileLock lock;
    private final TransactionalStore store;
    private final SignalMessenger messenger;
    private MessengerRuntime(FileChannel channel,FileLock lock,TransactionalStore store,SignalMessenger messenger){this.channel=channel;this.lock=lock;this.store=store;this.messenger=messenger;}
    public SignalMessenger messenger(){return messenger;}
    public org.uzel.messenger.chat.ChatRepository chatRepository(){return new org.uzel.messenger.chat.ChatRepository(store);}
    public static MessengerRuntime open(Path directory,SecretProtector protector,StoreFactory factory)throws Exception{
        Files.createDirectories(directory);
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new SecurityException("Invalid messenger directory");
        FileChannel channel=FileChannel.open(directory.resolve("profile.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock lock=null;TransactionalStore store=null;byte[] key=null;
        try{
            lock=channel.tryLock();if(lock==null)throw new IllegalStateException("Messenger is already open");
            Path db=directory.resolve("messages.db"),anchor=directory.resolve("commit.anchor");
            boolean hasDb=Files.exists(db),hasKey=Files.exists(directory.resolve(DeviceKeyVault.KEY_FILE)),hasAnchor=Files.exists(anchor);
            boolean fresh=!hasDb&&!hasKey&&!hasAnchor;
            if(!fresh&&!(hasDb&&hasKey&&hasAnchor))throw new SecurityException("Incomplete messenger profile requires recovery");
            key=DeviceKeyVault.loadOrCreate(directory,protector,hasDb);
            store=new AnchoredStore(factory.open(db,key),anchor,protector,fresh);
            String address;
            if(fresh)address=UUID.randomUUID()+":1";
            else{byte[] saved=store.read(r->r.get("local","address"));if(saved==null)throw new SecurityException("Missing messenger identity");address=new String(saved,StandardCharsets.UTF_8);}
            int separator=address.lastIndexOf(':');if(separator<0)throw new SecurityException("Invalid local address");
            var messenger=new SignalMessenger(store,new SignalProtocolAddress(address.substring(0,separator),Integer.parseInt(address.substring(separator+1))));
            if(fresh)messenger.initializeIdentity();else messenger.identity();
            return new MessengerRuntime(channel,lock,store,messenger);
        }catch(Exception|Error e){if(store!=null)try{store.close();}catch(Exception x){e.addSuppressed(x);}if(lock!=null)try{lock.release();}catch(Exception x){e.addSuppressed(x);}try{channel.close();}catch(Exception x){e.addSuppressed(x);}throw e;}
        finally{if(key!=null)Arrays.fill(key,(byte)0);}
    }
    @Override public synchronized void close()throws Exception{try{store.close();}finally{try{if(lock.isValid())lock.release();}finally{channel.close();}}}
}
