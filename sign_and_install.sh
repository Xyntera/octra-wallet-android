#!/usr/bin/env bash
# sign_and_install.sh
# Signs the APK and installs it on a connected Android device/emulator

set -e

APK="app/build/outputs/apk/release/app-release-unsigned.apk"
SIGNED_APK="app/build/outputs/apk/release/octra-wallet.apk"
KEYSTORE="octra-release.keystore"

if [ ! -f "$KEYSTORE" ]; then
    echo "Generating release keystore..."
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -alias octra \
        -keyalg RSA -keysize 2048 \
        -validity 10000 \
        -storepass octrapass \
        -keypass octrapass \
        -dname "CN=Octra Wallet, O=Octra, C=US"
fi

echo "Signing APK..."
# Requires build-tools in PATH — adjust path as needed
ZIPALIGN="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/34.0.0/zipalign"
APKSIGNER="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/34.0.0/apksigner"

"$ZIPALIGN" -v -p 4 "$APK" "${APK}.aligned"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:octrapass \
    --key-pass pass:octrapass \
    --out "$SIGNED_APK" \
    "${APK}.aligned"

echo "Signed APK: $SIGNED_APK"

if command -v adb &>/dev/null; then
    echo "Installing on device..."
    adb install -r "$SIGNED_APK"
    echo "Installed!"
else
    echo "adb not found — copy $SIGNED_APK to your device manually"
fi
