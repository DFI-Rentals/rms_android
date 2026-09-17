package com.dfirentals.rms;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Tracks whether any of our activities is on screen, so an incoming call alert can open directly. */
public class RmsApp extends Application {
    private static int started = 0;

    public static boolean isInForeground() { return started > 0; }

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { started++; }
            @Override public void onActivityStopped(Activity a) { started = Math.max(0, started - 1); }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}
