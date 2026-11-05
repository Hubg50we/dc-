package com.hubg50we.dc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private WebView webView;
    private DiscordManager discordManager;
    private ValueCallback<Uri[]> filePathCallback;

    private final ActivityResultLauncher<String> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (filePathCallback != null) {
                    if (uri != null) {
                        filePathCallback.onReceiveValue(new Uri[]{uri});
                    } else {
                        filePathCallback.onReceiveValue(null);
                    }
                    filePathCallback = null;
                }
            }
    );

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Request runtime notification permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        // Start background service to ensure accounts remain active 24/7
        Intent serviceIntent = new Intent(this, DiscordService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        discordManager = DiscordManager.getInstance(this);

        webView = new WebView(this);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMediaPlaybackRequiresUserGesture(false);
        try {
            settings.setRenderPriority(WebSettings.RenderPriority.HIGH);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.setSafeBrowsingEnabled(false);
            }
        } catch (Exception ignored) {}

        // Auto-request camera & microphone permissions if needed for WebRTC streaming
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO}, 101);
        }

        // Register high-performance bidirectional JavaScript bridge
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String path = request.getUrl().getPath();
                if (path != null && path.startsWith("/api/")) {
                    return handleApi(request, path);
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                MainActivity.this.filePathCallback = filePathCallback;
                filePickerLauncher.launch("*/*");
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/web/index.html");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    moveTaskToBack(true); // Don't kill, keep running in background
                }
            }
        });
    }

    private WebResourceResponse handleApi(WebResourceRequest request, String path) {
        try {
            if ("/api/status".equals(path)) {
                JSONObject status = discordManager.getStatusJson();
                byte[] bytes = status.toString().getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream(bytes));
            } else if ("/api/tokens".equals(path)) {
                JSONObject tokens = discordManager.getTokensJson();
                byte[] bytes = tokens.toString().getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream(bytes));
            } else if ("/api/presets".equals(path)) {
                JSONObject presets = discordManager.getPresetsJson();
                byte[] bytes = presets.toString().getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream(bytes));
            } else if ("/api/logs".equals(path)) {
                JSONObject logs = discordManager.getLogsJson();
                byte[] bytes = logs.toString().getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream(bytes));
            } else if ("/api/update".equals(path)) {
                JSONObject update = discordManager.checkUpdateJson();
                byte[] bytes = update.toString().getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream(bytes));
            }
        } catch (Exception e) {
            Log.e(TAG, "API intercept error: " + path, e);
        }
        return null;
    }

    private void installApk(File apkFile) {
        try {
            if (!apkFile.exists()) {
                Toast.makeText(this, "Downloaded APK not found!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    Toast.makeText(this, "Please enable permission to install updates", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                }
            }

            Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    apkFile
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Install APK error", e);
            Toast.makeText(this, "Failed to launch installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public String getData(String endpoint) {
            try {
                if ("/api/status".equals(endpoint)) {
                    return discordManager.getStatusJson().toString();
                } else if ("/api/tokens".equals(endpoint)) {
                    return discordManager.getTokensJson().toString();
                } else if ("/api/presets".equals(endpoint)) {
                    return discordManager.getPresetsJson().toString();
                } else if ("/api/logs".equals(endpoint)) {
                    return discordManager.getLogsJson().toString();
                } else if ("/api/update".equals(endpoint)) {
                    return discordManager.checkUpdateJson().toString();
                }
            } catch (Exception e) {
                Log.e(TAG, "Bridge getData error", e);
            }
            return "{}";
        }

        @JavascriptInterface
        public String checkUpdate() {
            try {
                return discordManager.checkUpdateJson().toString();
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
            }
        }

        @JavascriptInterface
        public void downloadAndInstallUpdate(String downloadUrl) {
            new Thread(() -> {
                try {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Downloading update...", Toast.LENGTH_SHORT).show());
                    OkHttpClient client = new OkHttpClient.Builder()
                            .connectTimeout(20, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .build();
                    Request request = new Request.Builder()
                            .url(downloadUrl)
                            .header("User-Agent", "Mozilla/5.0 (Android dc- app)")
                            .build();

                    try (Response response = client.newCall(request).execute()) {
                        if (!response.isSuccessful() || response.body() == null) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Download failed: HTTP " + response.code(), Toast.LENGTH_LONG).show());
                            return;
                        }

                        long totalBytes = response.body().contentLength();
                        File cacheDir = getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir();
                        File apkFile = new File(cacheDir, "dc-update.apk");
                        if (apkFile.exists()) apkFile.delete();

                        byte[] buffer = new byte[8192];
                        long downloadedBytes = 0;
                        try (InputStream in = response.body().byteStream();
                             FileOutputStream out = new FileOutputStream(apkFile)) {
                            int read;
                            long lastUpdate = 0;
                            while ((read = in.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                                downloadedBytes += read;
                                long now = System.currentTimeMillis();
                                if (now - lastUpdate > 250 || downloadedBytes == totalBytes) {
                                    lastUpdate = now;
                                    final int progress = totalBytes > 0 ? (int) ((downloadedBytes * 100) / totalBytes) : -1;
                                    final long dl = downloadedBytes;
                                    final long tot = totalBytes;
                                    runOnUiThread(() -> {
                                        if (webView != null) {
                                            webView.evaluateJavascript("if (window.onUpdateProgress) window.onUpdateProgress(" + progress + ", " + dl + ", " + tot + ");", null);
                                        }
                                    });
                                }
                            }
                            out.flush();
                        }

                        runOnUiThread(() -> {
                            if (webView != null) {
                                webView.evaluateJavascript("if (window.onUpdateComplete) window.onUpdateComplete();", null);
                            }
                            installApk(apkFile);
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Download update failed", e);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Download error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        @JavascriptInterface
        public void openBrowserUrl(String url) {
            runOnUiThread(() -> {
                try {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(browserIntent);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public String postAction(String endpoint, String bodyJson) {
            try {
                JSONObject payload = new JSONObject(bodyJson);
                if ("/api/tokens".equals(endpoint)) {
                    return discordManager.handleTokensPost(payload).toString();
                }
                return discordManager.handleAction(payload).toString();
            } catch (Exception e) {
                Log.e(TAG, "Bridge postAction error", e);
                return "{\"success\":false,\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
            }
        }

        @JavascriptInterface
        public void copyToClipboard(String text) {
            runOnUiThread(() -> {
                try {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("dc- export", text);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(MainActivity.this, "Copied to clipboard!", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Clipboard copy error", e);
                }
            });
        }

        @JavascriptInterface
        public void shareText(String title, String text) {
            runOnUiThread(() -> {
                try {
                    Intent sendIntent = new Intent();
                    sendIntent.setAction(Intent.ACTION_SEND);
                    sendIntent.putExtra(Intent.EXTRA_TEXT, text);
                    sendIntent.putExtra(Intent.EXTRA_TITLE, title);
                    sendIntent.setType("text/plain");
                    Intent shareIntent = Intent.createChooser(sendIntent, title);
                    startActivity(shareIntent);
                } catch (Exception e) {
                    Log.e(TAG, "Share error", e);
                }
            });
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(ms);
                    }
                }
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean isLowRamDevice() {
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    return am.isLowRamDevice();
                }
            } catch (Exception ignored) {}
            return false;
        }

        @JavascriptInterface
        public void requestBatteryOptimizationExemption() {
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                            @SuppressLint("BatteryLife")
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } else {
                            Toast.makeText(MainActivity.this, "Battery optimization is already unrestricted!", Toast.LENGTH_SHORT).show();
                        }
                    }
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                        startActivity(intent);
                    } catch (Exception ignored) {}
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
