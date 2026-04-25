#!/usr/bin/env bash
# ============================================================
# cross_compile.sh
# Cross-compiles octra_wallet for Android ARM64
# Run this on Linux BEFORE building the APK, or use CI workflow.
#
# Requirements:
#   - Android NDK r26+ installed
#   - Set NDK_PATH below or export NDK_PATH=/path/to/ndk
#   - Set WEBCLI_DIR to cloned octra-labs/webcli, or let it
#     auto-clone from GitHub (requires git + internet)
# ============================================================

set -e

NDK_PATH="${NDK_PATH:-$HOME/android-ndk-r26d}"
WEBCLI_DIR="${WEBCLI_DIR:-}"                   # auto-clone if empty
OUT_DIR="$(cd "$(dirname "$0")" && pwd)/app/src/main/assets"

TARGET=aarch64-linux-android
API=26
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"

WORK_DIR="$(pwd)/_build_android"
mkdir -p "$WORK_DIR"

echo "=== [1/6] Checking NDK ==="
if [ ! -f "$CXX" ]; then
    echo "ERROR: NDK not found at $NDK_PATH"
    echo "Download from: https://developer.android.com/ndk/downloads"
    echo "Then: export NDK_PATH=/path/to/ndk"
    exit 1
fi
echo "NDK OK: $CXX"

echo ""
echo "=== [2/6] Fetching webcli sources ==="
if [ -z "$WEBCLI_DIR" ]; then
    WEBCLI_DIR="$WORK_DIR/webcli"
    if [ ! -d "$WEBCLI_DIR/.git" ]; then
        echo "Cloning octra-labs/webcli..."
        git clone --depth=1 https://github.com/octra-labs/webcli.git "$WEBCLI_DIR"
    else
        echo "Updating octra-labs/webcli..."
        git -C "$WEBCLI_DIR" pull --ff-only
    fi
else
    echo "Using provided WEBCLI_DIR=$WEBCLI_DIR"
fi
echo "webcli commit: $(git -C "$WEBCLI_DIR" rev-parse --short HEAD 2>/dev/null || echo 'unknown')"

echo ""
echo "=== [3/6] Building OpenSSL 3.x for Android ARM64 ==="
OPENSSL_INSTALL="$WORK_DIR/openssl-install"

if [ ! -f "$OPENSSL_INSTALL/lib/libssl.a" ]; then
    OPENSSL_DIR="$WORK_DIR/openssl"
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
echo "=== [4/6] Building LevelDB for Android ARM64 ==="
LEVELDB_INSTALL="$WORK_DIR/leveldb-install"

if [ ! -f "$LEVELDB_INSTALL/lib/libleveldb.a" ]; then
    LEVELDB_DIR="$WORK_DIR/leveldb"
    if [ ! -d "$LEVELDB_DIR" ]; then
        echo "Downloading LevelDB 1.23..."
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
echo "=== [5/6] Cross-compiling octra_wallet from latest webcli ==="
cd "$WEBCLI_DIR"

# Show what we're building from
echo "Building from: $WEBCLI_DIR"
echo "Files: $(ls main.cpp wallet.hpp crypto_utils.hpp rpc_client.hpp 2>/dev/null || echo 'WARNING: some source files missing')"

# Compile C files
"$CC" -O2 -march=armv8-a+crypto -c lib/tweetnacl.c   -o "$WORK_DIR/tweetnacl.o"
"$CC" -O2 -march=armv8-a+crypto -c lib/randombytes.c  -o "$WORK_DIR/randombytes.o"

# Compile libpvac (static)
"$CXX" -std=c++17 -O2 -march=armv8-a+crypto -fPIC \
    -D_GNU_SOURCE \
    -I pvac/include \
    -c pvac/pvac_c_api.cpp \
    -o "$WORK_DIR/pvac_c_api.o"
"$AR" rcs "$WORK_DIR/libpvac.a" "$WORK_DIR/pvac_c_api.o"

# Compile main binary — fully static, no shared STL
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

BINARY_SIZE=$(du -sh "$WORK_DIR/octra_wallet_arm64" | cut -f1)
echo "Binary size: $BINARY_SIZE"
echo "octra_wallet_arm64 built OK"

echo ""
echo "=== [6/6] Copying assets into APK project ==="
mkdir -p "$OUT_DIR"
mkdir -p "$OUT_DIR/static"

# Copy native binary
cp "$WORK_DIR/octra_wallet_arm64" "$OUT_DIR/octra_wallet_arm64"
chmod 755 "$OUT_DIR/octra_wallet_arm64"
echo "Copied binary -> assets/octra_wallet_arm64 ($BINARY_SIZE)"

# Copy web UI assets from webcli static/
# Note: the wrapper's customized versions (index.html, wallet.js, style.css)
# will be committed directly in the repo and take precedence during APK build.
# Only copy files from upstream that are NOT customized in the wrapper.
UPSTREAM_ONLY_FILES=(
    "swap.html"
    "swap.js"
    "bridge.html"
)

for f in "${UPSTREAM_ONLY_FILES[@]}"; do
    if [ -f "$WEBCLI_DIR/static/$f" ]; then
        cp "$WEBCLI_DIR/static/$f" "$OUT_DIR/static/$f"
        echo "Copied $f from upstream"
    fi
done

# Copy templates and icons (always sync from upstream)
for d in templates icons; do
    if [ -d "$WEBCLI_DIR/static/$d" ]; then
        rm -rf "$OUT_DIR/static/$d"
        cp -r "$WEBCLI_DIR/static/$d" "$OUT_DIR/static/$d"
        echo "Copied $d/ from upstream"
    fi
done

cd -

echo ""
echo "============================================"
echo "  Cross-compilation complete!"
echo "  webcli commit: $(git -C "$WEBCLI_DIR" rev-parse --short HEAD 2>/dev/null)"
echo "  Binary: $BINARY_SIZE at assets/octra_wallet_arm64"
echo ""
echo "  Now build the APK:"
echo "    ./gradlew assembleRelease"
echo "  APK: app/build/outputs/apk/release/*.apk"
echo "============================================"
