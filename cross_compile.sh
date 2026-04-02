#!/usr/bin/env bash
# ============================================================
# cross_compile.sh
# Cross-compiles octra_wallet for Android ARM64
# Run this on Linux BEFORE building the APK
#
# Requirements:
#   - Android NDK r26+ installed
#   - Set NDK_PATH below or export NDK_PATH=/path/to/ndk
# ============================================================

set -e

NDK_PATH="${NDK_PATH:-$HOME/android-ndk-r26d}"
WEBCLI_DIR="${WEBCLI_DIR:-../webcli}"          # path to cloned octra-labs/webcli
OUT_DIR="$(pwd)/app/src/main/assets"

TARGET=aarch64-linux-android
API=26
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
SYSROOT="$TOOLCHAIN/sysroot"

WORK_DIR="$(pwd)/_build_android"
mkdir -p "$WORK_DIR"

echo "=== [1/5] Checking NDK ==="
if [ ! -f "$CXX" ]; then
    echo "ERROR: NDK not found at $NDK_PATH"
    echo "Download from: https://developer.android.com/ndk/downloads"
    echo "Then: export NDK_PATH=/path/to/ndk"
    exit 1
fi
echo "NDK OK: $CXX"

echo ""
echo "=== [2/5] Building OpenSSL 3.x for Android ARM64 ==="
OPENSSL_DIR="$WORK_DIR/openssl"
OPENSSL_INSTALL="$WORK_DIR/openssl-install"

if [ ! -f "$OPENSSL_INSTALL/lib/libssl.a" ]; then
    if [ ! -d "$OPENSSL_DIR" ]; then
        echo "Downloading OpenSSL 3.3.0..."
        curl -L https://github.com/openssl/openssl/releases/download/openssl-3.3.0/openssl-3.3.0.tar.gz \
            -o "$WORK_DIR/openssl.tar.gz"
        tar -xzf "$WORK_DIR/openssl.tar.gz" -C "$WORK_DIR"
        mv "$WORK_DIR/openssl-3.3.0" "$OPENSSL_DIR"
    fi
    cd "$OPENSSL_DIR"
    export ANDROID_NDK_ROOT="$NDK_PATH"
    PATH="$TOOLCHAIN/bin:$PATH" ./Configure android-arm64 \
        --prefix="$OPENSSL_INSTALL" \
        --openssldir="$OPENSSL_INSTALL" \
        no-shared no-tests \
        -D__ANDROID_API__=$API
    PATH="$TOOLCHAIN/bin:$PATH" make -j$(nproc) build_libs
    PATH="$TOOLCHAIN/bin:$PATH" make install_dev
    cd -
    echo "OpenSSL built OK"
else
    echo "OpenSSL already built, skipping"
fi

echo ""
echo "=== [3/5] Building LevelDB for Android ARM64 ==="
LEVELDB_DIR="$WORK_DIR/leveldb"
LEVELDB_INSTALL="$WORK_DIR/leveldb-install"

if [ ! -f "$LEVELDB_INSTALL/lib/libleveldb.a" ]; then
    if [ ! -d "$LEVELDB_DIR" ]; then
        echo "Downloading LevelDB..."
        curl -L https://github.com/google/leveldb/archive/refs/tags/1.23.tar.gz \
            -o "$WORK_DIR/leveldb.tar.gz"
        tar -xzf "$WORK_DIR/leveldb.tar.gz" -C "$WORK_DIR"
        mv "$WORK_DIR/leveldb-1.23" "$LEVELDB_DIR"
    fi
    mkdir -p "$LEVELDB_DIR/build_android"
    cd "$LEVELDB_DIR/build_android"
    cmake .. \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-$API \
        -DCMAKE_BUILD_TYPE=Release \
        -DLEVELDB_BUILD_TESTS=OFF \
        -DLEVELDB_BUILD_BENCHMARKS=OFF \
        -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_INSTALL_PREFIX="$LEVELDB_INSTALL"
    make -j$(nproc)
    make install
    cd -
    echo "LevelDB built OK"
else
    echo "LevelDB already built, skipping"
fi

echo ""
echo "=== [4/5] Cross-compiling octra_wallet ==="
cd "$WEBCLI_DIR"

# Compile C files
"$CC" -O2 -march=armv8-a+crypto -c lib/tweetnacl.c -o "$WORK_DIR/tweetnacl.o"
"$CC" -O2 -march=armv8-a+crypto -c lib/randombytes.c -o "$WORK_DIR/randombytes.o"

# Compile libpvac (static)
"$CXX" -std=c++17 -O2 -march=armv8-a+crypto -fPIC \
    -D_GNU_SOURCE \
    -I pvac/include \
    -c pvac/pvac_c_api.cpp \
    -o "$WORK_DIR/pvac_c_api.o"
"$AR" rcs "$WORK_DIR/libpvac.a" "$WORK_DIR/pvac_c_api.o"

# Compile main binary — fully static
"$CXX" -std=c++17 -O2 -march=armv8-a+crypto \
    -DCPPHTTPLIB_OPENSSL_SUPPORT \
    -D_GNU_SOURCE \
    -I . \
    -I pvac \
    -I pvac/include \
    -I "$OPENSSL_INSTALL/include" \
    -I "$LEVELDB_INSTALL/include" \
    main.cpp \
    "$WORK_DIR/tweetnacl.o" \
    "$WORK_DIR/randombytes.o" \
    -L "$WORK_DIR" -lpvac \
    -L "$OPENSSL_INSTALL/lib" -lssl -lcrypto \
    -L "$LEVELDB_INSTALL/lib" -lleveldb \
    -lz -ldl \
    -static-libstdc++ \
    -o "$WORK_DIR/octra_wallet_arm64"

echo "Binary size: $(du -sh $WORK_DIR/octra_wallet_arm64 | cut -f1)"
echo "octra_wallet_arm64 built OK"

echo ""
echo "=== [5/5] Copying assets into APK project ==="
mkdir -p "$OUT_DIR"

# Copy binary
cp "$WORK_DIR/octra_wallet_arm64" "$OUT_DIR/octra_wallet_arm64"
echo "Copied binary -> assets/octra_wallet_arm64"

# Copy static web UI
if [ -d "$WEBCLI_DIR/static" ]; then
    cp -r "$WEBCLI_DIR/static" "$OUT_DIR/static"
    echo "Copied static/ -> assets/static/"
else
    echo "WARNING: $WEBCLI_DIR/static not found — web UI will be missing"
fi

cd -

echo ""
echo "============================================"
echo "  Cross-compilation complete!"
echo "  Now build the APK:"
echo "    cd $(dirname $OUT_DIR)"
echo "    ./gradlew assembleRelease"
echo "  APK: app/build/outputs/apk/release/app-release-unsigned.apk"
echo "============================================"
