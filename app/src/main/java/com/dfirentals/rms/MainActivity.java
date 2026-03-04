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
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.view.KeyEvent;
import android.graphics.Bitmap;
import android.widget.ProgressBar;
import android.view.View;
import android.widget.Switch;
import android.widget.CompoundButton;
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
    private Switch modeSwitch;
    private TextView currentModeText;
    private boolean isKeyboardMode = false;
    private Handler handler = new Handler();
    private boolean isFirstResume = true;

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
        modeSwitch = findViewById(R.id.modeSwitch);
        currentModeText = findViewById(R.id.currentModeText);

        // Override WebView to prevent keyboard in scanner mode
        webView.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!isKeyboardMode && hasFocus) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
                    }
                }
            }
        });

        // Set up the mode switch listener
        modeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                isKeyboardMode = isChecked;
                if (isKeyboardMode) {
                    // Keyboard mode - show soft keyboard
                    currentModeText.setText("Keyboard Input");
                    webView.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
                    }
                } else {
                    // Scanner mode - hide soft keyboard
                    currentModeText.setText("Barcode Scanner Input");
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
                    }
                }
            }
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

        // Set WebViewClient to handle page navigation
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
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

        // Load the website
        webView.loadUrl("https://rms2.dfirentals.com");

        // Start keyboard suppression when in scanner mode
        startKeyboardSuppression();

        // Check for app updates on cold start (force check)
        UpdateManager updateManager = new UpdateManager(this);
        updateManager.checkForUpdates(true);
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
        UpdateManager updateManager = new UpdateManager(this);
        updateManager.checkForUpdates(false);
    }

    private Runnable keyboardSuppressor = new Runnable() {
        @Override
        public void run() {
            if (!isKeyboardMode) {
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
