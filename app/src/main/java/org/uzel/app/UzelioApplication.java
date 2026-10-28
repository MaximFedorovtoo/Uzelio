package org.uzel.app;
import android.app.Application;
import org.uzel.app.messenger.AndroidMessenger;
import org.uzel.messenger.MessengerRuntime;
import java.util.concurrent.CompletableFuture;

/** Owns one messenger per app process; no Activity recreation can open a second database. */
public final class UzelioApplication extends Application {
    private CompletableFuture<MessengerRuntime> messenger;
    @Override public void onCreate(){super.onCreate();start();}
    /** Lazily starts everything too, so a probe or a service never sees a null future. */
    private synchronized void start(){
        if(messenger!=null)return;
        org.uzel.app.messenger.ChatNotifications.ensure(this);
        messenger=CompletableFuture.supplyAsync(()->{try{return AndroidMessenger.open(this);}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}});
        // Open the chat listener with the application, not only when the "Chats" tab is first shown,
        // so incoming messages are accepted while the process is alive.
        messenger.thenRun(()->{try{AndroidMessenger.chats(this);}catch(Exception ignored){/* The tab reports an unusable store instead of falling back. */}});
        // Hold the process while the app is off screen, so the chat listener keeps delivering
        // messages and call offers; the foreground notification is silent and can be stopped.
        ChatLinkService.start(this);
    }
    /** An exceptional future means unavailable/recovery required, never a plaintext fallback. */
    public synchronized CompletableFuture<MessengerRuntime> messenger(){start();return messenger;}
}
