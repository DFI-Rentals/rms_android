package com.dfirentals.rms;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.view.KeyEvent;
import android.graphics.Bitmap;
import android.widget.ProgressBar;
import android.view.View;
import android.widget.FrameLayout;
import android.graphics.Typeface;
import android.animation.ValueAnimator;
import android.animation.ArgbEvaluator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.ViewGroup;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;
import android.provider.Settings;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONObject;
import java.util.UUID;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.os.Handler;
import android.widget.TextView;
import android.Manifest;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private ProgressBar progressBar;
    private FrameLayout modeGroup;
    private View segmentThumb;
    private TextView modeAuto, modeKeyboard, modeScanner;
    private ValueAnimator segmentAnimator;
    private boolean thumbPlaced = false;
    private static final long SEGMENT_ANIM_MS = 240;
    private View statusDot;
    private TextView currentModeText;
    private Handler handler = new Handler();
    private boolean isFirstResume = true;

    // ── Input mode ────────────────────────────────────────────────────────
    // The Zebra wedge types scans through the keyboard connection whatever we
    // do; "scanner mode" only means "keep the soft keyboard hidden". AUTO asks
    // the web app: every hidden scan-capture input in the RMS carries
    // data-scan-capture="" and the injected watcher below reports whether one
    // of them holds focus. KEYBOARD / SCANNER are manual overrides.
    private static final int MODE_AUTO = 0;
    private static final int MODE_KEYBOARD = 1;
    private static final int MODE_SCANNER = 2;
    private static final String PREFS = "rms_input";
    private static final String PREF_MODE = "input_mode";

    // ── Page URL ──────────────────────────────────────────────────────────
    // Debug builds accept an override so the app can point at a local RMS dev
    // server from the emulator (10.0.2.2 = the host machine):
    //   adb shell am start -n com.dfirentals.rms/.MainActivity --es rms_url http://10.0.2.2:5173
    // The override sticks until cleared with --es rms_url prod (or reinstall).
    // Release builds always load production. See dev-emulator.sh.
    private static final String DEFAULT_URL = "https://rms2.dfirentals.com";
    private static final String PREF_DEBUG_URL = "debug_url";
    private String pageUrl = DEFAULT_URL;
    private int inputMode = MODE_AUTO;
    private boolean scanFieldFocused = false; // reported by the page via RmsAndroid.onScanFocus

    /** Runs inside the page once per document load. Watches focus and tells us
     *  whether the active element is one of the RMS scan-capture inputs. */
    private static final String SCAN_WATCH_JS =
            "(function(){" +
            "if(window.__rmsScanWatch)return;window.__rmsScanWatch=true;" +
            "var last=null,t=null;" +
            "function report(){t=null;var a=document.activeElement;" +
            "var f=!!(a&&a.matches&&a.matches('[data-scan-capture]'));" +
            "if(f!==last){last=f;try{RmsAndroid.onScanFocus(f);}catch(e){}}}" +
            "function sched(){if(t)clearTimeout(t);t=setTimeout(report,30);}" +
            "document.addEventListener('focusin',sched,true);" +
            "document.addEventListener('focusout',sched,true);" +
            "document.addEventListener('visibilitychange',sched,true);" +
            "setInterval(report,1000);" + // belt and braces: catches focus() calls that fire no event we see
            "report();" +
            "})();";

    /** Exposed to the page as window.RmsAndroid. */
    private class RmsAndroidBridge {
        @JavascriptInterface
        public void onScanFocus(final boolean focused) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (scanFieldFocused == focused) return;
                    scanFieldFocused = focused;
                    applyInputMode();
                }
            });
        }

        /**
         * Device identity for POST /rms/devices/register: a stable per-install id,
         * hardware + OS + app version, Google Play services presence, and the
         * current FCM token (null until Firebase hands one over).
         */
        @JavascriptInterface
        public String getDeviceInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("device_id", deviceId());
                o.put("android_id", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
                o.put("manufacturer", Build.MANUFACTURER);
                o.put("model", Build.MODEL);
                o.put("os_version", Build.VERSION.RELEASE);
                o.put("sdk_int", Build.VERSION.SDK_INT);
                o.put("app_version", BuildConfig.VERSION_NAME);
                o.put("app_id", getPackageName());
                o.put("gms", hasGooglePlayServices());
                String token = RmsMessagingService.storedToken(MainActivity.this);
                o.put("fcm_token", token == null ? JSONObject.NULL : token);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String getInputMode() {
            return inputMode == MODE_KEYBOARD ? "keyboard" : inputMode == MODE_SCANNER ? "scanner" : "auto";
        }
    }

    /** Stable per-install device id (UUID minted once, kept in SharedPreferences). */
    private String deviceId() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String id = p.getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private boolean hasGooglePlayServices() {
        try {
            getPackageManager().getPackageInfo("com.google.android.gms", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Ask Firebase for the token at startup so getDeviceInfo() has it by the time the RMS logs in. */
    private void refreshFcmToken() {
        try {
            FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
                if (token == null) return;
                getSharedPreferences(RmsMessagingService.PREFS, MODE_PRIVATE).edit()
                        .putString(RmsMessagingService.PREF_TOKEN, token)
                        .putLong(RmsMessagingService.PREF_TOKEN_AT, System.currentTimeMillis()).apply();
            });
        } catch (Exception ignored) {
        }
    }

    private boolean shouldHideKeyboard() {
        if (inputMode == MODE_SCANNER) return true;
        if (inputMode == MODE_KEYBOARD) return false;
        return scanFieldFocused;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
        }
    }

    /** Re-evaluate after the mode or the page's focus report changes. */
    private void applyInputMode() {
        if (shouldHideKeyboard()) {
            hideKeyboard();
        } else if (inputMode == MODE_KEYBOARD) {
            webView.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
            }
        }
        // AUTO + no scan field focused: do nothing — the WebView shows the
        // keyboard itself when a normal field takes focus.
        updateModeText();
    }

    /** Status dot + label (the RMS "Ready to scan" indicator) and the active segment. */
    private void updateModeText() {
        boolean scannerLive = shouldHideKeyboard();
        String text;
        if (inputMode == MODE_SCANNER) {
            text = "Scanner only";
        } else if (inputMode == MODE_KEYBOARD) {
            text = "Keyboard only";
        } else {
            text = scanFieldFocused ? "Scanner ready" : "Keyboard";
        }
        currentModeText.setText(text);
        statusDot.setBackgroundResource(scannerLive ? R.drawable.status_dot_on : R.drawable.status_dot_off);

        moveThumbTo(activeSegment(), thumbPlaced);
    }

    private TextView activeSegment() {
        return inputMode == MODE_KEYBOARD ? modeKeyboard : inputMode == MODE_SCANNER ? modeScanner : modeAuto;
    }

    /** Slide + resize the white thumb under `target` and crossfade the label colors (ease in-out). */
    private void moveThumbTo(final TextView target, boolean animate) {
        if (target.getWidth() == 0) {
            // Not laid out yet (first frame): place it once layout is done, without animating.
            target.post(new Runnable() {
                @Override public void run() { moveThumbTo(target, false); }
            });
            return;
        }
        final int toX = target.getLeft();
        final int toW = target.getWidth();
        final int activeColor = getResources().getColor(R.color.rms_text_primary);
        final int inactiveColor = getResources().getColor(R.color.rms_text_secondary);
        final TextView[] segs = { modeAuto, modeKeyboard, modeScanner };

        if (segmentAnimator != null) segmentAnimator.cancel();

        if (!animate) {
            setThumb(toX, toW);
            for (TextView seg : segs) seg.setTextColor(seg == target ? activeColor : inactiveColor);
            thumbPlaced = true;
            return;
        }

        final float fromX = segmentThumb.getTranslationX();
        final int fromW = segmentThumb.getWidth();
        final int[] fromColors = new int[segs.length];
        for (int i = 0; i < segs.length; i++) fromColors[i] = segs[i].getCurrentTextColor();
        final ArgbEvaluator argb = new ArgbEvaluator();

        segmentAnimator = ValueAnimator.ofFloat(0f, 1f);
        segmentAnimator.setDuration(SEGMENT_ANIM_MS);
        segmentAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        segmentAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (float) a.getAnimatedValue();
                setThumb(Math.round(fromX + (toX - fromX) * t), Math.round(fromW + (toW - fromW) * t));
                for (int i = 0; i < segs.length; i++) {
                    int to = segs[i] == target ? activeColor : inactiveColor;
                    segs[i].setTextColor((Integer) argb.evaluate(t, fromColors[i], to));
                }
            }
        });
        segmentAnimator.start();
    }

    private void setThumb(int x, int width) {
        ViewGroup.LayoutParams lp = segmentThumb.getLayoutParams();
        if (lp.width != width) {
            lp.width = width;
            segmentThumb.setLayoutParams(lp);
        }
        segmentThumb.setTranslationX(x);
    }

    private void setInputMode(int mode) {
        inputMode = mode;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(PREF_MODE, mode).apply();
        applyInputMode();
    }

    // File upload support
    private static final int FILE_CHOOSER_REQUEST = 100;
    private static final int CAMERA_PERMISSION_REQUEST = 101;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 102;
    private ValueCallback<Uri[]> fileUploadCallback;
    private String cameraPhotoPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progressBar);
        modeGroup = findViewById(R.id.modeGroup);
        segmentThumb = findViewById(R.id.segmentThumb);
        modeAuto = findViewById(R.id.modeAuto);
        modeKeyboard = findViewById(R.id.modeKeyboard);
        modeScanner = findViewById(R.id.modeScanner);
        statusDot = findViewById(R.id.statusDot);
        currentModeText = findViewById(R.id.currentModeText);

        // Saans, the RMS typeface (assets/fonts). Falls back to the system font.
        try {
            Typeface saansMedium = Typeface.createFromAsset(getAssets(), "fonts/Saans-Medium.otf");
            Typeface saansRegular = Typeface.createFromAsset(getAssets(), "fonts/Saans-Regular.otf");
            currentModeText.setTypeface(saansRegular);
            modeAuto.setTypeface(saansMedium);
            modeKeyboard.setTypeface(saansMedium);
            modeScanner.setTypeface(saansMedium);
        } catch (RuntimeException ignored) {
        }

        // Restore the last chosen mode (Auto by default)
        inputMode = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_MODE, MODE_AUTO);
        updateModeText();

        // Keep the keyboard down when the WebView takes focus while a scan field is active
        webView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus && shouldHideKeyboard()) {
                    hideKeyboard();
                }
            }
        });

        modeAuto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setInputMode(MODE_AUTO); }
        });
        modeKeyboard.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setInputMode(MODE_KEYBOARD); }
        });
        modeScanner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setInputMode(MODE_SCANNER); }
        });

        // Configure WebView settings
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Page → app focus reports (only @JavascriptInterface methods are exposed)
        webView.addJavascriptInterface(new RmsAndroidBridge(), "RmsAndroid");

        // Set WebViewClient to handle page navigation
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
                // New document: nothing is focused until the page says otherwise
                if (scanFieldFocused) {
                    scanFieldFocused = false;
                    updateModeText();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                // The RMS is a single-page app, so this runs once per full load;
                // the watcher's document listeners survive in-app navigation.
                view.evaluateJavascript(SCAN_WATCH_JS, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Debug builds pointed at a local dev server may hit Vite's
                // self-signed cert (HTTPS=1 npm run dev). Never relaxed in release.
                if (BuildConfig.DEBUG && !pageUrl.equals(DEFAULT_URL)) {
                    handler.proceed();
                } else {
                    super.onReceivedSslError(view, handler, error);
                }
            }
        });

        // Set WebChromeClient for progress updates and file upload handling
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                progressBar.setProgress(newProgress);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                // Cancel any existing callback
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = callback;

                // Check if the accept types indicate image capture
                String[] acceptTypes = fileChooserParams.getAcceptTypes();
                boolean wantsImage = false;
                if (acceptTypes != null) {
                    for (String type : acceptTypes) {
                        if (type != null && type.contains("image")) {
                            wantsImage = true;
                            break;
                        }
                    }
                }

                // Check if capture mode is requested
                boolean captureMode = fileChooserParams.isCaptureEnabled();

                if (wantsImage && captureMode) {
                    // Direct camera capture — check permission first
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
                        return true;
                    }
                    launchCamera();
                } else {
                    // Show chooser with both camera and file picker options
                    launchFileChooser(wantsImage);
                }
                return true;
            }
        });

        // Load the website (debug builds may be pointed at a local dev server).
        // An alert's Accept / tap hands us a path to open (rms_path).
        pageUrl = resolvePageUrl();
        webView.loadUrl(deepLinkUrl(getIntent()));

        refreshFcmToken();

        // Alerts: channels exist from the start; Android 13+ needs the runtime
        // notification permission or nothing (including the call screen) shows.
        AlertNotifier.ensureChannels(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, NOTIFICATION_PERMISSION_REQUEST);
        }

        // Start keyboard suppression when in scanner mode
        startKeyboardSuppression();

        // Check for app updates on cold start (force check). Debug builds skip
        // it: a local build is never something GitHub should replace.
        if (!BuildConfig.DEBUG && !isPreviewFlavor()) {
            UpdateManager updateManager = new UpdateManager(this);
            updateManager.checkForUpdates(true);
        }
    }

    /** The side-by-side preview install must never try to "update" itself from GitHub. */
    private boolean isPreviewFlavor() {
        return getPackageName().endsWith(".preview");
    }

    /** base + path for alert deep links (path like /apps/waitlist); base alone when no path. */
    private static String withPath(String base, String path) {
        if (path == null || path.trim().isEmpty()) return base;
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path : "/" + path;
        return b + p;
    }

    /**
     * URL to load for an intent: the alert's deep link, plus ?rms_alert=<id> when
     * the intent came from an alert so the web app can acknowledge it (which
     * silences the other devices). Plain launches load the base URL.
     */
    private String deepLinkUrl(Intent intent) {
        String path = intent == null ? null : intent.getStringExtra("rms_path");
        String alertId = intent == null ? null : intent.getStringExtra(AlertNotifier.EXTRA_ID);
        String url = withPath(pageUrl, path);
        if (alertId != null && !alertId.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + "rms_alert=" + Uri.encode(alertId);
        }
        return url;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (webView != null && (intent.hasExtra("rms_path") || intent.hasExtra(AlertNotifier.EXTRA_ID))) {
            webView.loadUrl(deepLinkUrl(intent));
        }
    }

    private String resolvePageUrl() {
        if (!BuildConfig.DEBUG) return DEFAULT_URL;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("rms_url")) {
            String override = intent.getStringExtra("rms_url");
            if (override == null || override.trim().isEmpty() || override.trim().equalsIgnoreCase("prod")) {
                prefs.edit().remove(PREF_DEBUG_URL).apply();
            } else {
                prefs.edit().putString(PREF_DEBUG_URL, override.trim()).apply();
            }
        }
        String saved = prefs.getString(PREF_DEBUG_URL, null);
        return saved != null ? saved : DEFAULT_URL;
    }

    private void launchCamera() {
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (cameraIntent.resolveActivity(getPackageManager()) != null) {
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (photoFile != null) {
                cameraPhotoPath = photoFile.getAbsolutePath();
                Uri photoUri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", photoFile);
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                startActivityForResult(cameraIntent, FILE_CHOOSER_REQUEST);
            } else {
                cancelFileUpload();
            }
        } else {
            cancelFileUpload();
        }
    }

    private void launchFileChooser(boolean wantsImage) {
        // Camera intent
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        File photoFile = null;
        try {
            photoFile = createImageFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (photoFile != null) {
            cameraPhotoPath = photoFile.getAbsolutePath();
            Uri photoUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", photoFile);
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
        }

        // File picker intent
        Intent fileIntent = new Intent(Intent.ACTION_GET_CONTENT);
        fileIntent.addCategory(Intent.CATEGORY_OPENABLE);
        fileIntent.setType(wantsImage ? "image/*" : "*/*");

        // Combine into chooser
        Intent chooserIntent = Intent.createChooser(fileIntent, "Choose an option");
        if (photoFile != null) {
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
        }
        startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST);
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "QC_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile(fileName, ".jpg", storageDir);
    }

    private void cancelFileUpload() {
        if (fileUploadCallback != null) {
            fileUploadCallback.onReceiveValue(null);
            fileUploadCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileUploadCallback == null) return;

            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK) {
                if (data != null && data.getData() != null) {
                    // File was picked from gallery/files
                    results = new Uri[]{data.getData()};
                } else if (cameraPhotoPath != null) {
                    // Photo was taken with camera
                    File photoFile = new File(cameraPhotoPath);
                    if (photoFile.exists() && photoFile.length() > 0) {
                        results = new Uri[]{Uri.fromFile(photoFile)};
                    }
                }
            }

            fileUploadCallback.onReceiveValue(results);
            fileUploadCallback = null;
            cameraPhotoPath = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCamera();
            } else {
                // Permission denied — fall back to file picker
                launchFileChooser(true);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Skip the first onResume (happens right after onCreate)
        if (isFirstResume) {
            isFirstResume = false;
            return;
        }

        // Check for updates when returning from background (throttled)
        if (!BuildConfig.DEBUG && !isPreviewFlavor()) {
            UpdateManager updateManager = new UpdateManager(this);
            updateManager.checkForUpdates(false);
        }
    }

    private Runnable keyboardSuppressor = new Runnable() {
        @Override
        public void run() {
            if (shouldHideKeyboard()) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null && imm.isActive()) {
                    imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
                }
            }
            handler.postDelayed(this, 100);
        }
    };

    private void startKeyboardSuppression() {
        handler.post(keyboardSuppressor);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(keyboardSuppressor);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Handle back button to navigate WebView history
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        webView.restoreState(savedInstanceState);
    }
}
