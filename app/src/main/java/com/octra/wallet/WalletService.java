package com.octra.wallet;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class WalletService extends Service {

    private static final String TAG = "WalletService";
    static final int PORT = 8420;

    private Process serverProcess;
    private final IBinder localBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        WalletService getService() { return WalletService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return localBinder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (serverProcess == null || !serverProcess.isAlive()) {
            new Thread(this::setupAndStart).start();
        }
        return START_STICKY;
    }

    private void setupAndStart() {
        try {
            File filesDir  = getFilesDir();
            File dataDir   = new File(filesDir, "data");
            File staticDir = new File(filesDir, "static");

            // Android extracts jniLibs to the app's native lib dir — always executable
            File binary = new File(getApplicationInfo().nativeLibraryDir, "liboctra_wallet.so");

            dataDir.mkdirs();

            // Extract static/ web UI into filesDir/static/
            if (!new File(staticDir, "index.html").exists()) {
                if (staticDir.exists()) deleteDir(staticDir);
                extractAssetDir("static", staticDir);
            }

            File index = new File(staticDir, "index.html");
            Log.i(TAG, "binary=" + binary.getAbsolutePath() + " exists=" + binary.exists() + " exec=" + binary.canExecute());
            Log.i(TAG, "static/index.html exists=" + index.exists());

            ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath(), String.valueOf(PORT));
            pb.directory(filesDir);
            pb.redirectErrorStream(true);
            serverProcess = pb.start();
            pb.redirectErrorStream(true);
            serverProcess = pb.start();

            // Log all server output
            new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(serverProcess.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) Log.i(TAG, line);
                } catch (IOException ignored) {}
                try {
                    int code = serverProcess.waitFor();
                    Log.e(TAG, "Server exited with code " + code);
                } catch (InterruptedException ignored) {}
            }).start();

        } catch (Exception e) {
            Log.e(TAG, "Setup failed: " + e.getMessage(), e);
        }
    }

    private void deleteDir(File dir) {
        if (dir.isDirectory()) for (File f : dir.listFiles()) deleteDir(f);
        dir.delete();
    }

    private void extractAsset(String assetName, File dest) throws IOException {
        try (InputStream in = getAssets().open(assetName);
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
            File childDest = new File(destDir, child);
            String[] grandchildren = getAssets().list(childAsset);
            if (grandchildren != null && grandchildren.length > 0) {
                extractAssetDir(childAsset, childDest);
            } else {
                extractAsset(childAsset, childDest);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (serverProcess != null) { serverProcess.destroy(); serverProcess = null; }
        super.onDestroy();
    }
}
