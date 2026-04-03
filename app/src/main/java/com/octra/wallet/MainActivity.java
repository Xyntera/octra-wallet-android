package com.octra.wallet;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

public class MainActivity extends Activity {

    private WebView webView;
    private ProgressBar progressBar;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            waitForServerAndLoad();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // Cache-first: never revalidate localhost requests over network
        s.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        s.setRenderPriority(WebSettings.RenderPriority.HIGH);
        // Allow mixed content from localhost
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // Fit page to screen, no zoom controls
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        // Hardware acceleration
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // Suppress safe-browsing overhead for localhost
        WebView.setWebContentsDebuggingEnabled(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }
        });
        // Forward JS console errors to logcat
        webView.setWebChromeClient(new WebChromeClient());

        Intent intent = new Intent(this, WalletService.class);
        startService(intent);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    private void waitForServerAndLoad() {
        new Thread(() -> {
            // Poll up to 10 seconds in 100ms increments for fast cold-start
            for (int i = 0; i < 100; i++) {
                try {
                    java.net.Socket s = new java.net.Socket();
                    s.connect(new java.net.InetSocketAddress("127.0.0.1", WalletService.PORT), 200);
                    s.close();
                    runOnUiThread(() -> webView.loadUrl("http://127.0.0.1:" + WalletService.PORT));
                    return;
                } catch (Exception e) {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
            }
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                webView.loadData(
                    "<html><body style='background:#111;color:#f44;font-family:monospace;padding:20px'>" +
                    "<h2>Failed to start wallet server</h2>" +
                    "<p>Check logcat: <b>adb logcat -s WalletService</b></p>" +
                    "<p>Common causes:<br>" +
                    "- Binary not executable<br>" +
                    "- Missing static/ assets<br>" +
                    "- Port 8420 in use</p>" +
                    "<button onclick='location.reload()' style='padding:10px;margin-top:10px'>Retry</button>" +
                    "</body></html>",
                    "text/html", "utf-8");
            });
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        unbindService(connection);
        super.onDestroy();
    }
}
