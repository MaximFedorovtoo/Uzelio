package org.uzel.app.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import org.uzel.app.MainActivity;
import org.uzel.app.R;

/** System notifications for an incoming chat message and for an incoming call. */
public final class ChatNotifications {
    public static final String CHANNEL = "chats";
    public static final String CALL_CHANNEL = "calls";
    private static final int ID = 39283;
    private static final int CALL_ID = 39284;
    /** Matches the call ring timeout, so a ringing notification never outlives the offer. */
    private static final long CALL_TIMEOUT_MS = 60_000;

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

    /** Ringing channel: the system plays the ringtone and vibration; no looping service. */
    private static void ensureCallChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CALL_CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CALL_CHANNEL,
                context.getString(R.string.call_channel), NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 1000, 700, 1000, 700, 1000});
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }

    /**
     * Full-screen ringing notification for an offer received while the app is off screen.
     * Actions only open the Chats tab: the call is answered or declined by the call UI, so the
     * notification carries no signaling of its own.
     */
    public static void showIncomingCall(Context context, String contactName) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;
        ensure(context);
        ensureCallChannel(context);
        PendingIntent open = openChats(context, 10, null);
        PendingIntent accept = openChats(context, 11, "accept");
        PendingIntent decline = openChats(context, 12, "decline");
        Notification notification = new Notification.Builder(context, CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_node)
                .setContentTitle(contactName)
                .setContentText(context.getString(R.string.call_incoming))
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setTimeoutAfter(CALL_TIMEOUT_MS)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .addAction(new Notification.Action.Builder(null, context.getString(R.string.call_accept), accept).build())
                .addAction(new Notification.Action.Builder(null, context.getString(R.string.call_decline), decline).build())
                .build();
        manager.notify(CALL_ID, notification);
    }

    /** The offer ended, was answered elsewhere or was replaced: nothing may keep ringing. */
    public static void cancelIncomingCall(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(CALL_ID);
    }

    private static PendingIntent openChats(Context context, int requestCode, String callAction) {
        Intent open = new Intent(context, MainActivity.class).putExtra("tab", 4)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (callAction != null) open.putExtra("callAction", callAction);
        return PendingIntent.getActivity(context, requestCode, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
