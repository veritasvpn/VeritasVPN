#!/usr/bin/env bash
# Build/bundled userspace WireGuard for the macOS desktop app.
# End users do NOT run this — only developers / CI before `tauri build`.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../src-tauri/resources/bin" && pwd)"
mkdir -p "$ROOT"
TMP="$(mktemp -d)"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

git clone --depth 1 https://git.zx2c4.com/wireguard-go "$TMP/wg-go"
cd "$TMP/wg-go"
if [[ "$(uname -m)" == "arm64" ]]; then
  GOARCH=arm64 go build -o wireguard-go -ldflags=-s
else
  go build -o wireguard-go -ldflags=-s
fi
cp wireguard-go "$ROOT/wireguard-go"
chmod +x "$ROOT/wireguard-go"
echo "Bundled: $ROOT/wireguard-go ($(file "$ROOT/wireguard-go"))"
