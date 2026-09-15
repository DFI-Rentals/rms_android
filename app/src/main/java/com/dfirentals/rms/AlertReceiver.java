package com.dfirentals.rms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * Notification action handler (Accept / Dismiss) and the adb preview trigger.
 *
 * Preview (only honored while USB debugging is enabled on the device, so a
 * random app on the phone cannot ring it):
 *   adb shell am broadcast -a com.dfirentals.rms.ALERT -n com.dfirentals.rms/.AlertReceiver \
 *     --es kind call --es title "New waitlist customer" \
 *     --es body "Jordan Lee just joined the waitlist at the front desk." \
 *     --es deep_link /apps/waitlist --ei ttl 45
 */
public class AlertReceiver extends BroadcastReceiver {
    public static final String ACTION_PREVIEW = "com.dfirentals.rms.ALERT";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        String id = intent.getStringExtra(AlertNotifier.EXTRA_ID);

        if (AlertNotifier.ACTION_ACCEPT.equals(action)) {
            AlertNotifier.cancel(ctx, id);
            String path = intent.getStringExtra(AlertNotifier.EXTRA_PATH);
            Intent open = new Intent(ctx, MainActivity.class)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra("rms_path", path)
                    .putExtra(AlertNotifier.EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(open);
            // The web app reads ?rms_alert=<id> and POSTs /rms/notify/{id}/ack (silences the other devices)
        } else if (AlertNotifier.ACTION_DISMISS.equals(action)) {
            AlertNotifier.cancel(ctx, id);
        } else if (ACTION_PREVIEW.equals(action)) {
            boolean adb = Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
            if (!adb) return;
            if ("token".equals(intent.getStringExtra(AlertNotifier.EXTRA_KIND))) {
                // Dev aid: print the FCM token so a first push can be tested over USB
                android.util.Log.i("RmsDevice", "fcm_token=" + RmsMessagingService.storedToken(ctx));
                return;
            }
            AlertNotifier.show(ctx,
                    intent.getStringExtra(AlertNotifier.EXTRA_ID),
                    intent.getStringExtra(AlertNotifier.EXTRA_KIND) == null ? "call" : intent.getStringExtra(AlertNotifier.EXTRA_KIND),
                    intent.getStringExtra(AlertNotifier.EXTRA_TITLE) == null ? "RMS alert" : intent.getStringExtra(AlertNotifier.EXTRA_TITLE),
                    intent.getStringExtra(AlertNotifier.EXTRA_BODY) == null ? "" : intent.getStringExtra(AlertNotifier.EXTRA_BODY),
                    intent.getStringExtra(AlertNotifier.EXTRA_PATH),
                    intent.getIntExtra(AlertNotifier.EXTRA_TTL, 45));
        }
    }
}
