package com.dfirentals.rms;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
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

        @JavascriptInterface
        public String getInputMode() {
            return inputMode == MODE_KEYBOARD ? "keyboard" : inputMode == MODE_SCANNER ? "scanner" : "auto";
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

        // Load the website (debug builds may be pointed at a local dev server)
        pageUrl = resolvePageUrl();
        webView.loadUrl(pageUrl);

        // Start keyboard suppression when in scanner mode
        startKeyboardSuppression();

        // Check for app updates on cold start (force check). Debug builds skip
        // it: a local build is never something GitHub should replace.
        if (!BuildConfig.DEBUG) {
            UpdateManager updateManager = new UpdateManager(this);
            updateManager.checkForUpdates(true);
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
                        "com.dfirentals.rms.fileprovider", photoFile);
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
                    "com.dfirentals.rms.fileprovider", photoFile);
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
        if (!BuildConfig.DEBUG) {
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
