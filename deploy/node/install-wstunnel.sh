#!/usr/bin/env bash
# Install host-side wstunnel for VeritasVPN stealth WireGuard (TLS/WebSocket).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PREFIX="${STEALTH_PATH_PREFIX:-}"
LISTEN_PORT="${STEALTH_LISTEN_PORT:-443}"
WG_PORT="${WG_PORT:-51820}"
BIN_SRC="${WSTUNNEL_BIN:-}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root: sudo $0" >&2
  exit 1
fi

if [[ -z "$PREFIX" ]]; then
  if [[ -f /etc/veritasvpn/wstunnel.env ]]; then
    # shellcheck disable=SC1091
    source /etc/veritasvpn/wstunnel.env
    PREFIX="${STEALTH_PATH_PREFIX:-}"
  fi
fi
if [[ -z "$PREFIX" ]]; then
  PREFIX="$(openssl rand -hex 16)"
  echo "Generated STEALTH_PATH_PREFIX=$PREFIX"
fi

install -d -m 0755 /etc/veritasvpn /usr/local/bin

if [[ -z "$BIN_SRC" ]]; then
  if [[ -x /usr/local/bin/wstunnel ]]; then
    BIN_SRC=/usr/local/bin/wstunnel
  elif [[ -x "$ROOT/clients/desktop/src-tauri/resources/bin/wstunnel" ]]; then
    BIN_SRC="$ROOT/clients/desktop/src-tauri/resources/bin/wstunnel"
  else
    echo "Set WSTUNNEL_BIN to a wstunnel binary path" >&2
    exit 1
  fi
fi

install -m 0755 "$BIN_SRC" /usr/local/bin/wstunnel

cat >/etc/veritasvpn/wstunnel.env <<EOF
STEALTH_PATH_PREFIX=${PREFIX}
STEALTH_LISTEN_PORT=${LISTEN_PORT}
WG_PORT=${WG_PORT}
EOF
chmod 0640 /etc/veritasvpn/wstunnel.env

install -m 0644 "$ROOT/deploy/node/wstunnel.service" /etc/systemd/system/wstunnel.service
systemctl daemon-reload
systemctl enable --now wstunnel.service
systemctl --no-pager --full status wstunnel.service || true

echo
echo "Stealth listener: TCP ${LISTEN_PORT} → 127.0.0.1:${WG_PORT}"
echo "Set wg-manager STEALTH_PATH_PREFIX=${PREFIX} STEALTH_ENABLED=true"
echo "Router: forward WAN TCP ${LISTEN_PORT} to this host (UDP WireGuard stays separate)."
