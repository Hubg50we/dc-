package com.hubg50we.dc;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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

        // Start background service to ensure accounts remain active
        Intent serviceIntent = new Intent(this, DiscordService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        discordManager = DiscordManager.getInstance(this);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

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
                JSONObject presets = new JSONObject();
                presets.put("presets", new org.json.JSONArray());
                byte[] bytes = presets.toString().getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream(bytes));
            } else if ("/api/action".equals(path)) {
                // Read request body if available
                JSONObject payload = new JSONObject();
                try {
                    // For GET or simple requests: parse query params
                    Uri uri = request.getUrl();
                    for (String key : uri.getQueryParameterNames()) {
                        payload.put(key, uri.getQueryParameter(key));
                    }
                } catch (Exception ignored) {}

                JSONObject result = discordManager.handleAction(payload);
                byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream(bytes));
            }
        } catch (Exception e) {
            Log.e(TAG, "API intercept error: " + path, e);
        }
        return null;
    }
}
