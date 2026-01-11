package com.dfirentals.rms;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateManager {
    private Activity activity;
    private static final String GITHUB_RELEASES_API = "https://api.github.com/repos/DFI-Rentals/rms_android/releases/latest";
    private String downloadUrl = null;
    private String latestVersionName = null;
    private long downloadId = -1;

    public UpdateManager(Activity activity) {
        this.activity = activity;
    }

    public void checkForUpdates() {
        new Thread(() -> {
            try {
                // Get current version
                int currentVersion = getCurrentVersion();
                String currentVersionName = getCurrentVersionName();

                // Fetch latest release from GitHub API
                URL url = new URL(GITHUB_RELEASES_API);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                // Read response
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // Parse JSON response
                JSONObject release = new JSONObject(response.toString());
                String tagName = release.getString("tag_name"); // e.g., "v1.2"
                latestVersionName = tagName.replace("v", ""); // "1.2"

                // Get APK download URL from assets
                JSONArray assets = release.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String assetName = asset.getString("name");
                    if (assetName.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url");
                        break;
                    }
                }

                // Compare versions (simple string comparison for X.Y format)
                if (downloadUrl != null && isNewerVersion(currentVersionName, latestVersionName)) {
                    activity.runOnUiThread(() -> showUpdateDialog());
                }
            } catch (Exception e) {
                e.printStackTrace();
                // Silently fail - don't bother user with update check failures
            }
        }).start();
    }

    private boolean isNewerVersion(String current, String latest) {
        try {
            String[] currentParts = current.split("\\.");
            String[] latestParts = latest.split("\\.");

            for (int i = 0; i < Math.min(currentParts.length, latestParts.length); i++) {
                int currentNum = Integer.parseInt(currentParts[i]);
                int latestNum = Integer.parseInt(latestParts[i]);

                if (latestNum > currentNum) {
                    return true;
                } else if (latestNum < currentNum) {
                    return false;
                }
            }

            // If all parts are equal, check if latest has more parts
            return latestParts.length > currentParts.length;
        } catch (Exception e) {
            return false;
        }
    }

    private int getCurrentVersion() {
        try {
            PackageInfo pInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return pInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return 0;
        }
    }

    private String getCurrentVersionName() {
        try {
            PackageInfo pInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return "0.0";
        }
    }

    private void showUpdateDialog() {
        String message = "Version " + latestVersionName + " is now available. Would you like to download and install it?";
        new AlertDialog.Builder(activity)
            .setTitle("Update Available")
            .setMessage(message)
            .setPositiveButton("Update", (dialog, which) -> downloadAndInstallUpdate())
            .setNegativeButton("Later", null)
            .show();
    }

    private void downloadAndInstallUpdate() {
        if (downloadUrl == null) {
            return;
        }

        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
            request.setTitle("DFI Rentals RMS Update");
            request.setDescription("Downloading version " + latestVersionName + "...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "rms-update.apk");

            DownloadManager downloadManager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            downloadId = downloadManager.enqueue(request);

            // Register receiver to install APK when download completes
            BroadcastReceiver onComplete = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == downloadId) {
                        installUpdate(downloadManager);
                        context.unregisterReceiver(this);
                    }
                }
            };

            activity.registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void installUpdate(DownloadManager downloadManager) {
        try {
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);
            Cursor cursor = downloadManager.query(query);

            if (cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                if (DownloadManager.STATUS_SUCCESSFUL == cursor.getInt(columnIndex)) {
                    String uriString = cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI));
                    File file = new File(Uri.parse(uriString).getPath());

                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    Uri apkUri;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        apkUri = FileProvider.getUriForFile(activity,
                            activity.getPackageName() + ".fileprovider", file);
                        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else {
                        apkUri = Uri.fromFile(file);
                    }

                    intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                }
            }
            cursor.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
