package com.streakline.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Hosts the tracker UI (assets/index.html) in a WebView.
 * All data is kept in a single JSON file in the app's private storage.
 * The app has no INTERNET permission, so nothing can leave the phone.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "file:///android_asset/index.html";
    private static final String STATE_FILE = Reminders.STATE_FILE;
    private static final int REQ_EXPORT = 11;
    private static final int REQ_IMPORT = 12;

    private WebView web;
    private String pendingExport;
    private final Object fileLock = new Object();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(getResources().getColor(R.color.bg, getTheme()));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Stay inside the bundled page; ignore any other navigation.
                return !request.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");

        setContentView(web);
        web.loadUrl(START_URL);

        // Re-arm reminders (alarms are dropped on app updates and force-stops).
        Reminders.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            js("window.onNotifChanged && window.onNotifChanged()");
        }
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("Android");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web == null) {
            finish();
            return;
        }
        web.evaluateJavascript(
                "(window.onAndroidBack && window.onAndroidBack()) ? '1' : '0'",
                value -> {
                    if (!"\"1\"".equals(value)) finish();
                });
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;

        if (requestCode == REQ_EXPORT) {
            boolean ok = false;
            if (uri != null && pendingExport != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri, "w")) {
                    if (os != null) {
                        os.write(pendingExport.getBytes(StandardCharsets.UTF_8));
                        ok = true;
                    }
                } catch (Exception ignored) {
                }
            }
            pendingExport = null;
            js("window.onBackupSaved && window.onBackupSaved(" + ok + ")");
        } else if (requestCode == REQ_IMPORT && uri != null) {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    String text = readAll(is);
                    js("window.onBackupPicked && window.onBackupPicked(" + JSONObject.quote(text) + ")");
                }
            } catch (Exception e) {
                js("window.onBackupPicked && window.onBackupPicked('not a backup')");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        js("window.onNotifChanged && window.onNotifChanged()");
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void js(String code) {
        if (web != null) web.post(() -> {
            if (web != null) web.evaluateJavascript(code, null);
        });
    }

    private static String readAll(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    /** Methods callable from JavaScript as window.Android.* */
    private class Bridge {

        @JavascriptInterface
        public String loadState() {
            synchronized (fileLock) {
                File f = new File(getFilesDir(), STATE_FILE);
                if (!f.exists()) return null;
                try (FileInputStream in = new FileInputStream(f)) {
                    return readAll(in);
                } catch (Exception e) {
                    return null;
                }
            }
        }

        @JavascriptInterface
        public void saveState(String json) {
            if (json == null) return;
            synchronized (fileLock) {
                File tmp = new File(getFilesDir(), STATE_FILE + ".tmp");
                File dst = new File(getFilesDir(), STATE_FILE);
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                } catch (Exception e) {
                    return;
                }
                // Write-then-rename so a crash never leaves a half-written file.
                if (!tmp.renameTo(dst)) {
                    dst.delete();
                    tmp.renameTo(dst);
                }
            }
        }

        @JavascriptInterface
        public void exportBackup(String json, String fileName) {
            runOnUiThread(() -> {
                pendingExport = json;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, fileName);
                startActivityForResult(i, REQ_EXPORT);
            });
        }

        @JavascriptInterface
        public void pickBackup() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_IMPORT);
            });
        }

        /** times: "HH:mm,HH:mm" (empty string turns reminders off). */
        @JavascriptInterface
        public void setReminders(String times) {
            Reminders.setTimes(MainActivity.this, times);
            if (times != null && !times.trim().isEmpty()) runOnUiThread(MainActivity.this::askNotificationPermission);
        }

        @JavascriptInterface
        public void testNotification() {
            runOnUiThread(() -> {
                askNotificationPermission();
                Reminders.show(MainActivity.this, true);
            });
        }

        @JavascriptInterface
        public boolean notificationsAllowed() {
            return Reminders.notificationsAllowed(MainActivity.this);
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            runOnUiThread(() -> {
                Intent i;
                if (Build.VERSION.SDK_INT >= 26) {
                    i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                } else {
                    i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                }
                try {
                    startActivity(i);
                } catch (Exception ignored) {
                }
            });
        }

        /** 0 = light tap, 2 = success. */
        @JavascriptInterface
        public void haptic(int kind) {
            if (web == null) return;
            web.post(() -> {
                if (web != null) web.performHapticFeedback(kind == 2
                        ? HapticFeedbackConstants.LONG_PRESS
                        : HapticFeedbackConstants.KEYBOARD_TAP);
            });
        }
    }
}
