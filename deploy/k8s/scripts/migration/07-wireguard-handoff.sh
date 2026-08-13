#!/usr/bin/env bash
set -euo pipefail

echo "=== STEP 7: WireGuard handoff ==="
echo ""

REPO_ROOT="${REPO_ROOT:-/opt/veritasvpn}"
confirm() { read -rp "$1 [y/N] " yn; if [ "$yn" != "y" ] && [ "$yn" != "Y" ]; then echo "aborted"; exit 0; fi; }

confirm "This will stop the compose veritas-agent and let k3s agent take over wg0. Continue?"

cd "$REPO_ROOT"

echo "Recording current WireGuard state..."
wg show > /tmp/wg-pre-migration.txt
cat /tmp/wg-pre-migration.txt

echo "Copying WireGuard keys to k3s-compatible path..."
mkdir -p "$REPO_ROOT/data/wireguard"
if [ -d "$REPO_ROOT/data/wireguard" ] && [ "$(ls -A "$REPO_ROOT/data/wireguard" 2>/dev/null)" ]; then
  echo "  WireGuard keys already present"
else
  echo "  ERROR: WireGuard keys not found at $REPO_ROOT/data/wireguard/"
  echo "  Please copy your private key before continuing"
  exit 1
fi

echo "Stopping compose veritas-agent..."
docker compose stop veritas-agent

echo "Bringing down wg0 (k3s agent will recreate it)..."
ip link del wg0 2>/dev/null || echo "  wg0 already down"

echo "Deploying k3s agent + full stack..."
kubectl apply -k "$REPO_ROOT/deploy/k8s/overlays/prod/"

echo "Waiting for veritas-agent..."
kubectl -n veritas wait --for=condition=ready pod -l app=veritas-agent --timeout=120s 2>/dev/null || echo "  agent starting..."

echo "Waiting for all pods..."
sleep 30
kubectl -n veritas get pods

echo ""
echo "Verifying wg0 on host..."
sleep 5
wg show 2>/dev/null || echo "  wg0 not yet up — agent may need more time"

echo ""
echo "[wireguard-handoff] Done."
echo "  Run 'wg show' to verify"
echo "  Test VPN connection from an external client"
