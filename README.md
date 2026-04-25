# Octra Wallet — Android APK

Full Android wrapper for [octra-labs/webcli](https://github.com/octra-labs/webcli).  
Zero feature loss. All features work: send, encrypt/decrypt, stealth tx, FHE, HD wallet, contracts.

## How it works

1. `cross_compile.sh` cross-compiles `octra_wallet` for Android ARM64 on your Linux PC
2. The binary + web UI are bundled inside the APK as assets
3. On launch, the app extracts the binary, starts the server on port 8420, and opens it in a WebView

## Requirements

- Linux PC with:
  - Android NDK r26+ ([download](https://developer.android.com/ndk/downloads))
  - Android SDK with build-tools 34
  - `cmake`, `curl`, `make`
- Android device: API 26+ (Android 8.0+), ARM64

## Build Steps

### Step 1 — Clone webcli
```bash
git clone https://github.com/octra-labs/webcli ../webcli
```

### Step 2 — Cross-compile binary + copy assets
```bash
export NDK_PATH=/path/to/android-ndk-r26d
export WEBCLI_DIR=../webcli
chmod +x cross_compile.sh
./cross_compile.sh
```
This downloads OpenSSL + LevelDB, cross-compiles everything statically, and copies:
- `app/src/main/assets/octra_wallet_arm64` — the server binary
- `app/src/main/assets/static/` — the web UI

### Step 3 — Build APK
```bash
./gradlew assembleRelease
```

### Step 4 — Sign and install
```bash
chmod +x sign_and_install.sh
./sign_and_install.sh
```

Or just sideload `app/build/outputs/apk/release/octra-wallet.apk` manually.

## What happens on the phone

1. App installs
2. First launch: extracts binary to private app storage, starts server
3. WebView opens `http://127.0.0.1:8420` automatically
4. Full wallet UI — identical to desktop

## NOTES

- Wallet data stored in app's private directory (`/data/data/com.octra.wallet/files/data/`)
- Server runs as a background service — stays alive while app is open
- No internet permission needed for the server itself (it talks to Octra RPC nodes)
- Not suitable for Play Store (policy against executing native binaries from assets)
- Sideload via ADB or direct APK install
