package com.dfirentals.rms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

/**
 * Builds the RMS alerts. Three kinds share one entry point so the push
 * transport (FCM later, the adb preview trigger today) never cares about UI:
 *   call  - full-screen IncomingAlertActivity: screen on, over the lock screen,
 *           looping ringtone + vibration, Accept / Dismiss.
 *   alert - heads-up notification with sound.
 *   info  - ordinary notification.
 */
public final class AlertNotifier {
    public static final String CHANNEL_CALL = "rms_call";
    public static final String CHANNEL_ALERT = "rms_alert";
    public static final String CHANNEL_INFO = "rms_info";

    public static final String EXTRA_ID = "alert_id";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_BODY = "body";
    public static final String EXTRA_PATH = "deep_link";
    public static final String EXTRA_TTL = "ttl";

    public static final String ACTION_ACCEPT = "com.dfirentals.rms.ALERT_ACCEPT";
    public static final String ACTION_DISMISS = "com.dfirentals.rms.ALERT_DISMISS";
    /** Local signal: the alert was handled; any showing IncomingAlertActivity should close. */
    public static final String ACTION_DONE = "com.dfirentals.rms.ALERT_DONE";

    private AlertNotifier() {}

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel call = new NotificationChannel(CHANNEL_CALL, "Incoming RMS alerts",
                NotificationManager.IMPORTANCE_HIGH);
        call.setDescription("Call-style alerts that need someone right now, like a new waitlist customer");
        Uri ring = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        call.setSound(ring, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        call.enableVibration(true);
        call.setVibrationPattern(new long[]{0, 600, 400, 600});
        call.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        call.setBypassDnd(true);
        nm.createNotificationChannel(call);

        NotificationChannel alert = new NotificationChannel(CHANNEL_ALERT, "RMS alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("Important RMS events that should be seen soon");
        nm.createNotificationChannel(alert);

        NotificationChannel info = new NotificationChannel(CHANNEL_INFO, "RMS updates",
                NotificationManager.IMPORTANCE_DEFAULT);
        info.setDescription("Routine RMS notifications");
        nm.createNotificationChannel(info);
    }

    public static int idFor(String alertId) {
        return alertId == null ? 1 : Math.abs(alertId.hashCode()) % 100000 + 10;
    }

    public static void show(Context ctx, String alertId, String kind, String title, String body, String path, int ttlSeconds) {
        ensureChannels(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (alertId == null || alertId.isEmpty()) alertId = "adhoc-" + System.currentTimeMillis();
        // No deep link yet (the waitlist page does not exist): Accept / tap opens the RMS home page.
        if (path == null || path.trim().isEmpty()) path = "/dashboard";
        int notifId = idFor(alertId);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        // Tapping the notification body opens the RMS at the deep link
        Intent open = new Intent(ctx, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra("rms_path", path)
                .putExtra(EXTRA_ID, alertId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(ctx, notifId, open, piFlags);

        boolean isCall = "call".equals(kind);
        String channel = isCall ? CHANNEL_CALL : "alert".equals(kind) ? CHANNEL_ALERT : CHANNEL_INFO;

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, channel)
                : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_stat_rms)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(0xFF7DD654);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(isCall ? Notification.PRIORITY_MAX : Notification.PRIORITY_HIGH);
            b.setDefaults(Notification.DEFAULT_ALL);
        }

        if (isCall) {
            Intent full = new Intent(ctx, IncomingAlertActivity.class)
                    .putExtra(EXTRA_ID, alertId).putExtra(EXTRA_KIND, kind)
                    .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_BODY, body)
                    .putExtra(EXTRA_PATH, path).putExtra(EXTRA_TTL, ttlSeconds)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent fullPi = PendingIntent.getActivity(ctx, notifId + 1, full, piFlags);

            Intent accept = new Intent(ctx, AlertReceiver.class).setAction(ACTION_ACCEPT)
                    .putExtra(EXTRA_ID, alertId).putExtra(EXTRA_PATH, path);
            Intent dismiss = new Intent(ctx, AlertReceiver.class).setAction(ACTION_DISMISS)
                    .putExtra(EXTRA_ID, alertId);
            PendingIntent acceptPi = PendingIntent.getBroadcast(ctx, notifId + 2, accept, piFlags);
            PendingIntent dismissPi = PendingIntent.getBroadcast(ctx, notifId + 3, dismiss, piFlags);

            b.setCategory(Notification.CATEGORY_CALL)
                    .setOngoing(true)
                    .setFullScreenIntent(fullPi, true)
                    .setContentIntent(fullPi)
                    .addAction(new Notification.Action.Builder(null, "Accept", acceptPi).build())
                    .addAction(new Notification.Action.Builder(null, "Dismiss", dismissPi).build())
                    .setTimeoutAfter(Math.max(10, ttlSeconds) * 1000L);
        }

        nm.notify(notifId, b.build());
    }

    public static void cancel(Context ctx, String alertId) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(idFor(alertId));
        ctx.sendBroadcast(new Intent(ACTION_DONE).setPackage(ctx.getPackageName()).putExtra(EXTRA_ID, alertId));
    }
}
