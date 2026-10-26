package org.uzel.desktop.messenger;
import org.uzel.messenger.MessengerRuntime;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.uzel.messenger.chat.ChatService;
import org.uzel.desktop.IdentityStore;
import org.uzel.core.NetworkAccess;
import java.net.InetAddress;

/** Shared by the desktop application lifecycle and native chat UI. */
public final class DesktopMessenger {
    private static CompletableFuture<MessengerRuntime> runtime;
    private static CompletableFuture<ChatService> chats;
    private DesktopMessenger(){}
    public static MessengerRuntime open(Path appData)throws Exception{return MessengerRuntime.open(appData.resolve("messenger"),new WindowsKeyProtector(),JdbcMessageStore::new);}
    public static synchronized CompletableFuture<MessengerRuntime> start(Path appData){
        if(runtime==null){runtime=CompletableFuture.supplyAsync(()->{try{return open(appData);}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}});
            Runtime.getRuntime().addShutdownHook(new Thread(()->{try{if(chats!=null&&!chats.isCompletedExceptionally())chats.join().close();}catch(Exception ignored){}try{runtime.join().close();}catch(Exception ignored){/* Failed startup exposes no writable messenger. */}},"messenger-close"));}
        return runtime;
    }
    public static synchronized CompletableFuture<MessengerRuntime> runtime(){if(runtime==null)throw new IllegalStateException("Messenger has not started");return runtime;}
    public static synchronized CompletableFuture<ChatService> chats(Path appData){
        if(chats==null||chats.isCompletedExceptionally())chats=start(appData).thenApplyAsync(r->{try{return new ChatService(r,IdentityStore.load(appData),new NetworkAccess(),InetAddress.getByName("0.0.0.0"),39283);}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}});
        return chats;
    }
}
