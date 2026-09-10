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
case "$ARCH" in
  linux_amd64) EXPECTED_SHA256=db6064cca0515b67f8652e201cff8e27553b8cbb7216b2e19241311e34868e6e ;;
  linux_arm64) EXPECTED_SHA256=26bb36b856948255bec7cd71a39df5f8912acdd7a47a9ccd4044a9b80ced108d ;;
  *) echo "unsupported wstunnel architecture: $ARCH" >&2; exit 1 ;;
esac
printf '%s  %s\n' "$EXPECTED_SHA256" "$TMP/wst.tar.gz" | sha256sum --check --strict
tar -xzf "$TMP/wst.tar.gz" -C "$TMP"
BIN="$(find "$TMP" -type f -name 'wstunnel' | head -1)"
if [[ -z "$BIN" ]]; then
  echo "wstunnel binary not found in archive" >&2
  exit 1
fi
install -m 0755 "$BIN" "$ROOT/wstunnel"
echo "Bundled: $ROOT/wstunnel ($(file "$ROOT/wstunnel"))"
