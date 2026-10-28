package org.uzel.app.messenger;
import android.content.Context;
import org.uzel.messenger.MessengerRuntime;
import org.uzel.messenger.chat.ChatRepository;
import org.uzel.messenger.chat.ChatService;
import org.uzel.messenger.chat.ChatTransport;
import org.uzel.app.NodeController;
import org.uzel.app.MainActivity;
import org.uzel.app.UzelioApplication;
import org.uzel.app.DeviceIdentity;
import org.uzel.core.NetworkAccess;
import org.uzel.core.TlsIdentity;
import android.net.*;
import java.net.*;
import java.io.IOException;
import java.util.concurrent.*;

public final class AndroidMessenger {
    /** Call signaling multiplexed onto the same authenticated chat listener as notifications. */
    public interface CallHandler {void call(String contactId,int kind,String callId,String sdp);}
    private static CompletableFuture<ChatService> chats;
    private static volatile CallHandler callHandler;
    private AndroidMessenger(){}
    /** The screen owns call UI; a failing handler must never disturb message delivery. */
    public static void setCallHandler(CallHandler handler){callHandler=handler;}
    /** Clears only this handler, so a recreated screen is never detached by an old one. */
    public static void clearCallHandler(CallHandler handler){if(handler!=null&&callHandler==handler)callHandler=null;}
    public static MessengerRuntime open(Context context)throws Exception{
        return MessengerRuntime.open(context.getNoBackupFilesDir().toPath().resolve("messenger"),new AndroidKeyProtector(),(path,key)->new AndroidMessageStore(path.toFile(),key));
    }
    public static synchronized CompletableFuture<ChatService> chats(Context context){
        Context app=context.getApplicationContext();
        if(chats==null||chats.isCompletedExceptionally())chats=((UzelioApplication)app).messenger().thenApplyAsync(runtime->{try{
            TlsIdentity identity=DeviceIdentity.load();
            NetworkAccess network=physicalNetwork(app);
            ChatTransport.Factory factory=(authorizer,receiver)->new AndroidChatTransport(identity,
                    ChatTransport.pinned(identity,network,InetAddress.getByName("0.0.0.0"),39283,authorizer,receiver),
                    fingerprint->NodeController.get(app).openChatStream(fingerprint));
            ChatService service=new ChatService(runtime,identity,factory,network);
            service.listener(new ChatService.Listener(){
                @Override public void incoming(String contactId,ChatRepository.Message message){
                    if(MainActivity.visible&&MainActivity.activeTab==4)return;
                    ChatNotifications.show(app,service.contactName(contactId),message.text().isBlank()&&message.attachment()!=null?"Файл: "+message.attachment().name():message.text());
                }
                @Override public void call(String contactId,int kind,String callId,String sdp){
                    CallHandler handler=callHandler;
                    if(handler==null)return;
                    try{handler.call(contactId,kind,callId,sdp);}catch(RuntimeException ignored){/* UI only */}
                }
            });
            return service;
        }catch(Exception e){throw new CompletionException(e);}});
        return chats;
    }
    /** Select a live non-VPN network on every connection, including after Wi-Fi changes. */
    private static NetworkAccess physicalNetwork(Context app){return new NetworkAccess(){
        private Network network()throws IOException{
            ConnectivityManager cm=(ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active=cm.getActiveNetwork();NetworkCapabilities caps=active==null?null:cm.getNetworkCapabilities(active);
            if(caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))return active;
            for(Network n:cm.getAllNetworks()){caps=cm.getNetworkCapabilities(n);if(caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))return n;}
            throw new IOException("No physical network available");
        }
        @Override public InetAddress[] resolve(String host)throws IOException{return network().getAllByName(host);}
        @Override public Socket open(String host,int port)throws IOException{Network n=network();return connect(n,n.getAllByName(host)[0],port);}
        @Override public Socket open(InetAddress address,int port)throws IOException{return connect(network(),address,port);}
        private Socket connect(Network n,InetAddress address,int port)throws IOException{
            Socket socket=n.getSocketFactory().createSocket();try{socket.connect(new InetSocketAddress(address,port),5000);socket.setSoTimeout(15000);socket.setTcpNoDelay(true);return socket;}catch(IOException e){socket.close();throw e;}
        }
    };}
}
