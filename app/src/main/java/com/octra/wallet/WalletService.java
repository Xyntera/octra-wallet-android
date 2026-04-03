package com.octra.wallet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class WalletService extends Service {

    private static final String TAG         = "WalletService";
    static final         int    PORT        = 8420;
    private static final String CHANNEL_ID  = "octra_wallet_service";
    private static final int    NOTIF_ID    = 1001;
    private static final String ACTION_STOP = "com.octra.wallet.ACTION_STOP";

    private java.lang.Process   serverProcess;
    private PowerManager.WakeLock cpuLock;
    private final IBinder         localBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        WalletService getService() { return WalletService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return localBinder; }

    // ── Lifecycle ────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireWakeLock();
        startForeground(NOTIF_ID, buildNotification("starting…"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle "Stop" action from notification
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (serverProcess == null || !serverProcess.isAlive()) {
            new Thread(this::setupAndStart).start();
        }
        return START_STICKY;   // restart if killed
    }

    @Override
    public void onDestroy() {
        if (serverProcess != null) { serverProcess.destroy(); serverProcess = null; }
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        stopForeground(true);
        super.onDestroy();
    }

    // ── Notification ─────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Octra Wallet Server",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps the wallet HTTP server alive");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String status) {
        // Tap → open app
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // "Stop" action
        Intent stopIntent = new Intent(this, WalletService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }

        b.setSmallIcon(android.R.drawable.ic_dialog_info)
         .setContentTitle("Octra Wallet")
         .setContentText("Server: " + status + "  |  port " + PORT)
         .setContentIntent(openPi)
         .setOngoing(true)
         .setCategory(Notification.CATEGORY_SERVICE)
         .addAction(android.R.drawable.ic_delete, "Stop server", stopPi);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void updateNotification(String status) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(status));
    }

    // ── Wake Lock (keeps CPU running when screen is off) ─────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "octra:cpu_lock");
        cpuLock.setReferenceCounted(false);
        cpuLock.acquire();
    }

    // ── Server setup ─────────────────────────────────────────────────

    private void setupAndStart() {
        // Boost server thread to foreground priority
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);

        try {
            File filesDir  = getFilesDir();
            File dataDir   = new File(filesDir, "data");
            File staticDir = new File(filesDir, "static");
            File binary    = new File(getApplicationInfo().nativeLibraryDir,
                                      "liboctra_wallet.so");

            dataDir.mkdirs();

            // Re-extract assets when APK version changes (new CSS / JS updates)
            int currentVersion = 0;
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                currentVersion = pi.versionCode;
            } catch (Exception ignored) {}

            SharedPreferences prefs = getSharedPreferences("octra_prefs", MODE_PRIVATE);
            int lastVersion = prefs.getInt("extracted_version", -1);

            if (lastVersion != currentVersion || !new File(staticDir, "index.html").exists()) {
                updateNotification("extracting assets…");
                if (staticDir.exists()) deleteDir(staticDir);
                extractAssetDir("static", staticDir);
                prefs.edit().putInt("extracted_version", currentVersion).apply();
            }

            Log.i(TAG, "binary=" + binary.getAbsolutePath()
                + " exists=" + binary.exists()
                + " exec=" + binary.canExecute());

            if (!binary.exists()) {
                Log.e(TAG, "Binary missing — cannot start server");
                updateNotification("ERROR: binary missing");
                return;
            }

            updateNotification("starting server…");

            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(),
                                                   String.valueOf(PORT));
            pb.directory(filesDir);
            pb.redirectErrorStream(true);
            serverProcess = pb.start();   // ← start ONCE only

            updateNotification("running on :" + PORT);

            // Drain server stdout/stderr to logcat
            new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(serverProcess.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) Log.i(TAG, line);
                } catch (IOException ignored) {}
                try {
                    int code = serverProcess.waitFor();
                    Log.e(TAG, "Server exited with code " + code);
                    updateNotification("stopped (exit " + code + ")");
                } catch (InterruptedException ignored) {}
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Setup failed: " + e.getMessage(), e);
            updateNotification("ERROR: " + e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void deleteDir(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) deleteDir(f);
        }
        dir.delete();
    }

    private void extractAsset(String assetName, File dest) throws IOException {
        try (InputStream in  = getAssets().open(assetName);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private void extractAssetDir(String assetPath, File destDir) throws IOException {
        String[] children = getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            extractAsset(assetPath, destDir);
            return;
        }
        destDir.mkdirs();
        for (String child : children) {
            String childAsset = assetPath + "/" + child;
            File   childDest  = new File(destDir, child);
            String[] grands   = getAssets().list(childAsset);
            if (grands != null && grands.length > 0) {
                extractAssetDir(childAsset, childDest);
            } else {
                extractAsset(childAsset, childDest);
            }
        }
    }
}
