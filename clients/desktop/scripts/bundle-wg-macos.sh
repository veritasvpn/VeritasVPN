#!/usr/bin/env bash
# Build/bundled userspace WireGuard for the macOS desktop app.
# End users do NOT run this — only developers / CI before `tauri build`.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../src-tauri/resources/bin" && pwd)"
mkdir -p "$ROOT"
TMP="$(mktemp -d)"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

WIREGUARD_GO_COMMIT="ecfc5a8d54462e18e13c72173e2623d16d8e25a0"
git init -q "$TMP/wg-go"
git -C "$TMP/wg-go" remote add origin https://git.zx2c4.com/wireguard-go
git -C "$TMP/wg-go" fetch -q --depth 1 origin "$WIREGUARD_GO_COMMIT"
git -C "$TMP/wg-go" checkout -q --detach FETCH_HEAD
test "$(git -C "$TMP/wg-go" rev-parse HEAD)" = "$WIREGUARD_GO_COMMIT"
cd "$TMP/wg-go"
if [[ "$(uname -m)" == "arm64" ]]; then
  GOARCH=arm64 go build -o wireguard-go -ldflags=-s
else
  go build -o wireguard-go -ldflags=-s
fi
cp wireguard-go "$ROOT/wireguard-go"
chmod +x "$ROOT/wireguard-go"
echo "Bundled: $ROOT/wireguard-go ($(file "$ROOT/wireguard-go"))"
