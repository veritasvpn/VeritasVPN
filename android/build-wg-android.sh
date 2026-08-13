#!/usr/bin/env bash
# Build wireguard-go as a shared library for Android.
# Requires: Go 1.22+, Android NDK (ANDROID_NDK_HOME set)
# Output: app/src/main/jniLibs/<abi>/libwg-go.so
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIB_DIR="$SCRIPT_DIR/app/src/main/jniLibs"

ABIS=("arm64-v8a" "armeabi-v7a" "x86_64" "x86")
GOARCHS=("arm64" "arm" "amd64" "386")

for i in "${!ABIS[@]}"; do
    abi="${ABIS[$i]}"
    goarch="${GOARCHS[$i]}"
    outdir="$LIB_DIR/$abi"
    mkdir -p "$outdir"

    echo "Building for $abi ($goarch)..."
    CGO_ENABLED=1 \
    GOOS=android \
    GOARCH="$goarch" \
    CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang" \
    go build -buildmode=c-shared \
        -o "$outdir/libwg-go.so" \
        -ldflags="-s -w" \
        "$SCRIPT_DIR/wireguard/wgbackend.go"

    echo "  -> $outdir/libwg-go.so"
done

echo "Done. Libraries placed in $LIB_DIR"
