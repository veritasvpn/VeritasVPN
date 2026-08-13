#!/usr/bin/env bash
set -euo pipefail

echo "=== ROLLBACK: k3s → Docker Compose ==="
echo ""

REPO_ROOT="${REPO_ROOT:-/opt/veritasvpn}"
confirm() { read -rp "$1 [y/N] " yn; if [ "$yn" != "y" ] && [ "$yn" != "Y" ]; then echo "aborted"; exit 0; fi; }

confirm "ROLLBACK to Docker Compose? This will DELETE k3s resources and restart compose."

cd "$REPO_ROOT"

echo "1. Stopping k3s agent (daemonset)..."
kubectl -n veritas delete ds veritas-agent --ignore-not-found

echo "2. Bringing down wg0..."
ip link del wg0 2>/dev/null || true

echo "3. Deleting k3s resources..."
kubectl delete namespace veritas --wait --timeout=60s --ignore-not-found
kubectl delete namespace btcpay --wait --timeout=60s --ignore-not-found
kubectl -n ingress-nginx delete deploy cloudflared --ignore-not-found

echo "4. Starting compose stack..."
docker compose up -d

echo "5. Waiting for services..."
sleep 20
docker compose ps

echo "6. Re-running WireGuard bootstrap..."
bash deploy/node/bootstrap-wg.sh

echo "7. Restoring PostgreSQL if needed..."
echo "   If K8s destroyed compose data, restore from: ./backups/pre-k3s-*/pg_dumpall.sql.gz"
echo "   gunzip -c ./backups/pre-k3s-*/pg_dumpall.sql.gz | docker compose exec -T postgres psql -U veritas"

echo ""
echo "[rollback] Done. Verify with:"
echo "  wg show"
echo "  docker compose ps"
echo "  curl https://veritasvpn.cloud/healthz"
