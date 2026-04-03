package com.octra.wallet;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

public class MainActivity extends Activity {

    private WebView webView;
    private ProgressBar progressBar;
    private PowerManager.WakeLock wakeLock;
    private boolean bound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bound = true;
            waitForServerAndLoad();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on while app is open
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        webView  = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress);

        // ── WebView performance settings ─────────────────────────────
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        // Cache-first — no network round-trip for localhost assets
        s.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);

        // Rendering & zoom
        s.setRenderPriority(WebSettings.RenderPriority.HIGH);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        // Hardware GPU acceleration
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Boost WebView thread priority
        webView.post(() -> {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }
            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                // On error, retry loading after 1 second
                webView.postDelayed(
                    () -> webView.loadUrl("http://127.0.0.1:" + WalletService.PORT),
                    1000
                );
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        // ── Acquire partial wake lock so server thread keeps running ──
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "octra:server_wakelock");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        // ── Start foreground service (Termux-style) then bind ─────────
        Intent intent = new Intent(this, WalletService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    private void waitForServerAndLoad() {
        new Thread(() -> {
            // Boost background polling thread priority
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);

            // Poll every 100ms, up to 15 seconds
            for (int i = 0; i < 150; i++) {
                try {
                    java.net.Socket sock = new java.net.Socket();
                    sock.connect(new java.net.InetSocketAddress("127.0.0.1", WalletService.PORT), 200);
                    sock.close();
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.VISIBLE);
                        webView.loadUrl("http://127.0.0.1:" + WalletService.PORT);
                    });
                    return;
                } catch (Exception ignored) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                }
            }
            // Timeout — show error page
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                webView.loadData(
                    "<html><body style='background:#111;color:#f44;font-family:monospace;padding:20px'>" +
                    "<h2>&#9888; Server failed to start</h2>" +
                    "<p>The wallet server did not respond within 15 seconds.</p>" +
                    "<p>Common causes:<br>" +
                    "- Binary not compatible with this device<br>" +
                    "- Port 8420 already in use<br>" +
                    "- Insufficient permissions</p>" +
                    "<button onclick='location.reload()' style='background:#007AFF;color:#fff;" +
                    "border:none;padding:12px 24px;border-radius:10px;margin-top:10px;font-size:16px'>Retry</button>" +
                    "</body></html>",
                    "text/html", "utf-8");
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (bound) { unbindService(connection); bound = false; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
