#!/usr/bin/env bash
# Fetch bundled wstunnel for Linux Stealth mode (developers / CI before `tauri build`).
# End users do NOT run this.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../src-tauri/resources/bin" && pwd)"
mkdir -p "$ROOT"
VERSION="${WSTUNNEL_VERSION:-10.6.2}"
ARCH="${WSTUNNEL_ARCH:-linux_amd64}"
URL="https://github.com/erebe/wstunnel/releases/download/v${VERSION}/wstunnel_${VERSION}_${ARCH}.tar.gz"
TMP="$(mktemp -d)"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

echo "Downloading wstunnel v${VERSION} (${ARCH})…"
curl -fL --retry 5 -o "$TMP/wst.tar.gz" "$URL"
tar -xzf "$TMP/wst.tar.gz" -C "$TMP"
BIN="$(find "$TMP" -type f -name 'wstunnel' | head -1)"
if [[ -z "$BIN" ]]; then
  echo "wstunnel binary not found in archive" >&2
  exit 1
fi
install -m 0755 "$BIN" "$ROOT/wstunnel"
echo "Bundled: $ROOT/wstunnel ($(file "$ROOT/wstunnel"))"
