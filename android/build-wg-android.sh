#!/usr/bin/env bash
# Deprecated: libwg-go.so is bundled in com.wireguard.android:tunnel (Maven AAR).
# This script built a broken custom JNI-in-Go backend and is no longer used in CI.
# See tunnel AAR: jni/<abi>/libwg-go.so
set -euo pipefail
echo "error: build-wg-android.sh is deprecated; use the tunnel dependency native libs." >&2
exit 1
