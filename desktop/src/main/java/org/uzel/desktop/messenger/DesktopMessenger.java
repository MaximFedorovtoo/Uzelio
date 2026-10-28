package org.uzel.desktop.messenger;
import org.uzel.messenger.MessengerRuntime;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.uzel.messenger.chat.ChatService;
import org.uzel.messenger.chat.ChatTransport;
import org.uzel.messenger.chat.ChatRepository;
import org.uzel.messenger.chat.ChatWire;
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
    private static volatile java.util.function.BiConsumer<String,String> notifier=(name,text)->{};
    /** One UI owns call signaling; messages and calls share the single chat listener below. */
    public interface CallHandler {
        void call(ChatService service,String contactId,String name,int kind,String callId,String sdp);
    }
    private static volatile CallHandler callHandler=(service,contactId,name,kind,callId,sdp)->{};
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
    /** Tray notification hook; a failing notifier never affects delivery or storage. */
    public static void useNotifier(java.util.function.BiConsumer<String,String> value){notifier=value==null?(name,text)->{}:value;}
    /** Callback for encrypted CALL_OFFER/CALL_ANSWER/CALL_END frames; never called on the UI thread. */
    public static void setCallHandler(CallHandler value){callHandler=value==null?(service,contactId,name,kind,callId,sdp)->{}:value;}
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
                ChatService service=new ChatService(r,identity,factory,network);
                service.listener(new ChatService.Listener(){
                    public void incoming(String contactId,ChatRepository.Message message){
                        notifier.accept(service.contactName(contactId),message.text().isBlank()&&message.attachment()!=null?"Файл: "+message.attachment().name():message.text());
                    }
                    public void call(String contactId,int kind,String callId,String sdp){
                        try{
                            String name=kind==ChatWire.CALL_OFFER?service.contactName(contactId):"";
                            callHandler.call(service,contactId,name,kind,callId,sdp);
                        }catch(RuntimeException ignored){/* UI only */}
                    }
                });
                return service;
            }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}});
        }
        return chats;
    }
}
