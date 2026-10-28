package org.uzel.app.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.uzel.app.MainActivity;
import org.uzel.app.R;

/** System notification for an incoming chat message; tap opens the Chats tab. */
public final class ChatNotifications {
    public static final String CHANNEL = "chats";
    private static final int ID = 39283;

    private ChatNotifications() {}

    public static void ensure(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL) != null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                context.getString(R.string.chat_title), NotificationManager.IMPORTANCE_HIGH));
    }

    /** Shows one notification at a time; the Chats screen is the single source of history. */
    public static void show(Context context, String contactName, String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        ensure(context);
        Intent open = new Intent(context, MainActivity.class)
                .putExtra("tab", 4)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String preview = text.length() > 160 ? text.substring(0, 160) + "…" : text;
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_node)
                .setContentTitle(contactName)
                .setContentText(preview)
                .setStyle(new Notification.BigTextStyle().bigText(preview))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build();
        manager.notify(ID, notification);
    }
}
