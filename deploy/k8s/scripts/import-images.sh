#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
TAG="${TAG:?TAG must be set to an immutable version or digest}"
REGISTRY="${REGISTRY:-localhost:31500}"

need_cmd() { command -v "$1" >/dev/null || { echo "ERROR: $1 not found"; exit 1; }; }
need_cmd docker

if ! command -v k3s >/dev/null 2>&1; then
  echo "ERROR: k3s not installed yet (run install-k3s first)"
  exit 1
fi

import_one() {
  local src="$1"
  local dst="$2"
  echo "--- $src -> $dst ---"
  if ! docker image inspect "$src" >/dev/null 2>&1; then
    echo "  WARN: source image missing: $src"
    return 1
  fi
  docker tag "$src" "$dst"
  docker save "$dst" | k3s ctr images import -
  echo "  imported"
}

echo "Importing Veritas service images into k3s..."
PAIRS=(
  "veritasvpn-auth-svc:latest ${REGISTRY}/auth-svc:${TAG}"
  "veritasvpn-wg-manager:latest ${REGISTRY}/wg-manager:${TAG}"
  "veritasvpn-billing-svc:latest ${REGISTRY}/billing-svc:${TAG}"
  "veritasvpn-veritas-agent:latest ${REGISTRY}/veritas-agent:${TAG}"
  "veritasvpn-veritas-proxy:latest ${REGISTRY}/veritas-proxy:${TAG}"
)

missing=0
for pair in "${PAIRS[@]}"; do
  src="${pair%% *}"
  dst="${pair#* }"
  import_one "$src" "$dst" || missing=1
done

if [ "$missing" -ne 0 ]; then
  echo "Some compose images missing — building via docker compose..."
  cd "$REPO_ROOT"
  docker compose build auth-svc wg-manager billing-svc veritas-agent veritas-proxy
  for pair in "${PAIRS[@]}"; do
    src="${pair%% *}"
    dst="${pair#* }"
    import_one "$src" "$dst" || true
  done
fi

PUBLIC_IMAGES=(
  nginx:1.27-alpine
  postgres:16-alpine
  redis:7-alpine
  nats:2.10-alpine
  registry:2
  btcpayserver/bitcoin:28.1
  btcpayserver/btcpayserver:2.3.9
  nicolasdorier/nbxplorer:2.6.9
  cloudflare/cloudflared:2026.8.2
)

echo "Importing public images into k3s from local docker cache..."
for img in "${PUBLIC_IMAGES[@]}"; do
  if docker image inspect "$img" >/dev/null 2>&1; then
    echo "--- $img ---"
    docker save "$img" | k3s ctr images import -
  else
    echo "  skip (not local): $img"
  fi
done

echo ""
echo "k3s images (filtered):"
k3s ctr images ls | awk 'NR==1 || /localhost:31500|nginx|postgres|redis|nats|btcpay|nbxplorer|cloudflared|registry/' | head -40
echo "Done."
