package org.uzel.desktop.messenger;
import org.uzel.messenger.MessengerRuntime;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.uzel.messenger.chat.ChatService;
import org.uzel.messenger.chat.ChatTransport;
import org.uzel.core.TlsIdentity;
import org.uzel.desktop.IdentityStore;
import org.uzel.core.NetworkAccess;
import java.net.InetAddress;

/** Shared by the desktop application lifecycle and native chat UI. */
public final class DesktopMessenger {
    /** Fixed loopback chat TLS listener; the paired traversal carrier bridges chat streams here. */
    public static final int CHAT_PORT=39283;
    private static CompletableFuture<MessengerRuntime> runtime;
    private static CompletableFuture<ChatService> chats;
    private static DesktopChatTransport.ChatStreams streams;
    private DesktopMessenger(){}
    public static MessengerRuntime open(Path appData)throws Exception{return MessengerRuntime.open(appData.resolve("messenger"),new WindowsKeyProtector(),JdbcMessageStore::new);}
    public static synchronized CompletableFuture<MessengerRuntime> start(Path appData){
        if(runtime==null){runtime=CompletableFuture.supplyAsync(()->{try{return open(appData);}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}});
            Runtime.getRuntime().addShutdownHook(new Thread(()->{try{if(chats!=null&&!chats.isCompletedExceptionally())chats.join().close();}catch(Exception ignored){}try{runtime.join().close();}catch(Exception ignored){/* Failed startup exposes no writable messenger. */}},"messenger-close"));}
        return runtime;
    }
    public static synchronized CompletableFuture<MessengerRuntime> runtime(){if(runtime==null)throw new IllegalStateException("Messenger has not started");return runtime;}
    /** Installs the paired P2P fallback used only after the direct pinned mTLS address fails. */
    public static synchronized void useChatStreams(DesktopChatTransport.ChatStreams provider){streams=provider;}
    public static synchronized CompletableFuture<ChatService> chats(Path appData){
        if(chats==null||chats.isCompletedExceptionally()){
            DesktopChatTransport.ChatStreams p2p=streams;
            chats=start(appData).thenApplyAsync(r->{try{
                TlsIdentity identity=IdentityStore.load(appData);
                NetworkAccess network=new NetworkAccess();
                ChatTransport.Factory factory=(authorizer,receiver)->{
                    ChatTransport direct=ChatTransport.pinned(identity,network,InetAddress.getByName("0.0.0.0"),CHAT_PORT,authorizer,receiver);
                    return p2p==null?direct:new DesktopChatTransport(identity,direct,p2p);
                };
                return new ChatService(r,identity,factory,network);
            }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}});
        }
        return chats;
    }
}
