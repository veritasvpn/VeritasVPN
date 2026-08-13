#!/usr/bin/env bash
set -euo pipefail

echo "=== STEP 8: Stop compose, start k3s cloudflared ==="
echo ""

REPO_ROOT="${REPO_ROOT:-/opt/veritasvpn}"
confirm() { read -rp "$1 [y/N] " yn; if [ "$yn" != "y" ] && [ "$yn" != "Y" ]; then echo "aborted"; exit 0; fi; }

cd "$REPO_ROOT"

echo "Stopping ALL compose containers..."
docker compose down

echo "Verifying no compose containers remain..."
docker compose ps 2>/dev/null | grep -q . && echo "  WARNING: some containers still running" || echo "  all stopped"

echo "Deploying k3s cloudflared..."
kubectl apply -f "$REPO_ROOT/deploy/k8s/ingress-nginx/cloudflared.yaml"

echo "Waiting for cloudflared..."
kubectl -n ingress-nginx wait --for=condition=ready pod -l app=cloudflared --timeout=120s

echo "Checking all k3s pods..."
echo "--- veritas ---"
kubectl -n veritas get pods
echo ""
echo "--- ingress-nginx ---"
kubectl -n ingress-nginx get pods

echo ""
echo "[cutover] Done. Verify:"
echo "  1. curl https://veritasvpn.cloud/healthz"
echo "  2. WireGuard connection from external client"
echo "  3. Peer creation via API"
