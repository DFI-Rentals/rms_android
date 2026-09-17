package com.dfirentals.rms;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.TextView;

/**
 * The call-style alert: full screen, shown over the lock screen with the
 * screen turned on, looping ringtone + vibration until Accept, Dismiss, or
 * the ttl runs out. Accept opens the RMS at the alert's deep link.
 */
public class IncomingAlertActivity extends Activity {
    private String alertId;
    private String path;
    private MediaPlayer player;
    private Vibrator vibrator;
    private final Handler handler = new Handler();
    private Runnable timeout;
    private BroadcastReceiver doneReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Over the lock screen, screen on, keep it on while ringing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_incoming_alert);

        TextView titleView = findViewById(R.id.alertTitle);
        TextView bodyView = findViewById(R.id.alertBody);
        TextView kicker = findViewById(R.id.alertKicker);
        TextView accept = findViewById(R.id.alertAccept);
        TextView dismiss = findViewById(R.id.alertDismiss);
        View pulse = findViewById(R.id.alertPulse);

        applyIntent(getIntent());
        try {
            Typeface bold = Typeface.createFromAsset(getAssets(), "fonts/Saans-Medium.otf");
            Typeface regular = Typeface.createFromAsset(getAssets(), "fonts/Saans-Regular.otf");
            titleView.setTypeface(bold); kicker.setTypeface(bold); accept.setTypeface(bold); dismiss.setTypeface(bold);
            bodyView.setTypeface(regular);
        } catch (RuntimeException ignored) {}

        AlphaAnimation breathe = new AlphaAnimation(0.35f, 1f);
        breathe.setDuration(700); breathe.setRepeatMode(Animation.REVERSE); breathe.setRepeatCount(Animation.INFINITE);
        pulse.startAnimation(breathe);

        accept.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { accept(); }
        });
        dismiss.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });

        startRinging();

        // Another device (or the notification's action buttons) handled it
        doneReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String id = i.getStringExtra(AlertNotifier.EXTRA_ID);
                if (alertId == null || id == null || alertId.equals(id)) finishQuietly();
            }
        };
        IntentFilter f = new IntentFilter(AlertNotifier.ACTION_DONE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(doneReceiver, f, 4 /* Context.RECEIVER_NOT_EXPORTED, API 33 constant; compileSdk is 30 */);
        else registerReceiver(doneReceiver, f);
    }

    /** Show this alert's text and arm its timeout (also used when a newer page replaces the current one). */
    private void applyIntent(Intent in) {
        alertId = in.getStringExtra(AlertNotifier.EXTRA_ID);
        path = in.getStringExtra(AlertNotifier.EXTRA_PATH);
        String title = in.getStringExtra(AlertNotifier.EXTRA_TITLE);
        String body = in.getStringExtra(AlertNotifier.EXTRA_BODY);
        int ttl = in.getIntExtra(AlertNotifier.EXTRA_TTL, 45);
        ((TextView) findViewById(R.id.alertTitle)).setText(title == null ? "RMS alert" : title);
        ((TextView) findViewById(R.id.alertBody)).setText(body == null ? "" : body);
        handler.removeCallbacks(timeout);
        timeout = new Runnable() { @Override public void run() { dismiss(); } };
        handler.postDelayed(timeout, Math.max(10, ttl) * 1000L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
        if (player == null) startRinging();
    }

    private void startRinging() {
        try {
            Uri ring = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            player = new MediaPlayer();
            player.setDataSource(this, ring);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception ignored) { player = null; }
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 600, 400, 600, 1200};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            } else {
                vibrator.vibrate(pattern, 0);
            }
        }
    }

    private void stopRinging() {
        if (player != null) { try { player.stop(); player.release(); } catch (Exception ignored) {} player = null; }
        if (vibrator != null) vibrator.cancel();
        handler.removeCallbacks(timeout);
    }

    private void accept() {
        stopRinging();
        AlertNotifier.cancel(this, alertId);
        Intent open = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra("rms_path", path)
                .putExtra(AlertNotifier.EXTRA_ID, alertId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissSucceeded() { startActivity(open); finish(); }
                    @Override public void onDismissCancelled() { finish(); }
                    @Override public void onDismissError() { startActivity(open); finish(); }
                });
                return;
            }
        }
        startActivity(open);
        finish();
    }

    private void dismiss() {
        stopRinging();
        AlertNotifier.cancel(this, alertId);
        finish();
    }

    private void finishQuietly() {
        stopRinging();
        finish();
    }

    @Override
    public void onBackPressed() { dismiss(); }

    @Override
    protected void onDestroy() {
        stopRinging();
        if (doneReceiver != null) { try { unregisterReceiver(doneReceiver); } catch (Exception ignored) {} }
        super.onDestroy();
    }
}
