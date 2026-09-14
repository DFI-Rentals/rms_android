package com.dfirentals.rms;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Push transport. rms-notify publishes through SNS -> FCM as a DATA message
 * (high priority), so this runs whether the app is foreground, background,
 * or killed, and renders the alert through AlertNotifier - the same code
 * path the adb preview trigger uses.
 *
 * Data keys: type (alert|ack), alert_id, kind (call|alert|info), title, body,
 * deep_link, ttl.
 */
public class RmsMessagingService extends FirebaseMessagingService {
    private static final String TAG = "RmsMessaging";
    static final String PREFS = "rms_push";
    static final String PREF_TOKEN = "fcm_token";
    static final String PREF_TOKEN_AT = "fcm_token_at";

    @Override
    public void onNewToken(String token) {
        // Stored here; the web app reads it via RmsAndroid.getDeviceInfo() and
        // registers device + token + signed-in user with POST /rms/devices/register.
        SharedPreferences p = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(PREF_TOKEN, token).putLong(PREF_TOKEN_AT, System.currentTimeMillis()).apply();
        Log.i(TAG, "FCM token refreshed");
    }

    @Override
    public void onMessageReceived(RemoteMessage msg) {
        Map<String, String> d = msg.getData();
        String type = d.get("type");
        String alertId = d.get("alert_id");
        if ("ack".equals(type)) {
            // Someone else accepted: stop ringing here
            AlertNotifier.cancel(this, alertId);
            return;
        }
        int ttl = 45;
        try { ttl = Integer.parseInt(d.get("ttl")); } catch (Exception ignored) {}
        AlertNotifier.show(this, alertId,
                d.get("kind") == null ? "alert" : d.get("kind"),
                d.get("title") == null ? "RMS alert" : d.get("title"),
                d.get("body") == null ? "" : d.get("body"),
                d.get("deep_link"), ttl);
    }

    static String storedToken(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_TOKEN, null);
    }
}
