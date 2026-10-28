package org.uzel.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import org.uzel.app.messenger.AndroidMessenger;

/**
 * Keeps the process and the chat listener alive between screens, so incoming messages and call
 * offers are delivered while the app is not visible. The notification is deliberately silent and
 * low importance: it only explains why Uzelio stays in memory, and carries a Stop action.
 */
public final class ChatLinkService extends Service {
    public static final String ACTION_STOP = "org.uzel.app.action.STOP_CHAT_LINK";
    private static final String CHANNEL = "chat_link";
    private static final int NOTIFICATION_ID = 39282;

    /**
     * Best-effort start from the app process. A denied notification permission or a background
     * start rejected by the platform must never break application start: the app keeps working
     * while it is on screen and messages resume on the next launch.
     */
    public static void start(Context context) {
        Context app = context.getApplicationContext();
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager != null && !manager.areNotificationsEnabled()) return;
        Intent intent = new Intent(app, ChatLinkService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent);
            else app.startService(intent);
        } catch (RuntimeException refused) {
            // Android 12+ rejects foreground starts from the background; nothing to recover here.
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    getString(R.string.chat_link_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        try {
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID, notification());
        } catch (RuntimeException refused) {
            // Missing type/permission or an OEM restriction must not crash the app process.
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        // A restarted process has no listener yet: recreate it on the application instance.
        try { AndroidMessenger.chats(this); } catch (RuntimeException ignored) {/* The Chats tab reports it. */}
        return START_STICKY;
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ChatLinkService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_node)
                .setContentTitle(getString(R.string.chat_link_title))
                .setContentText(getString(R.string.chat_link_text))
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .addAction(new Notification.Action.Builder(null, getString(R.string.chat_link_stop), stop).build())
                .build();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
