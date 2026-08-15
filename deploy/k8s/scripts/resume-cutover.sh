#!/usr/bin/env bash
set -euo pipefail
export REPO_ROOT=/home/jpg/VeritasVPN
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
export BACKUP_DIR
BACKUP_DIR=$(ls -d "$REPO_ROOT"/backups/pre-k3s-* | tail -1)
cd "$REPO_ROOT"
LOG="$REPO_ROOT/backups/migrate-resume-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee -a "$LOG") 2>&1
echo "RESUME log $LOG backup $BACKUP_DIR"

echo "=== Re-apply BTCPay secrets + stack ==="
kubectl apply -k "$REPO_ROOT/deploy/k8s/btcpay/"

echo "=== Apply veritas prod overlay ==="
kubectl apply -k "$REPO_ROOT/deploy/k8s/overlays/prod/"

echo "=== Scale down apps for DB restore ==="
kubectl -n veritas scale deploy/auth-svc deploy/wg-manager deploy/billing-svc deploy/nginx deploy/veritas-proxy deploy/redis --replicas=0 2>/dev/null || true
kubectl -n veritas delete ds veritas-agent --ignore-not-found 2>/dev/null || true
kubectl -n veritas scale sts/nats --replicas=0 2>/dev/null || true

echo "=== Wait postgres ==="
kubectl -n veritas wait --for=condition=ready pod -l app=postgres --timeout=300s
POSTGRES_POD=$(kubectl -n veritas get pod -l app=postgres -o jsonpath='{.items[0].metadata.name}')
echo "Restoring into $POSTGRES_POD"
gunzip -c "$BACKUP_DIR/veritas-pg_dumpall.sql.gz" | kubectl -n veritas exec -i "$POSTGRES_POD" -- psql -U veritas -v ON_ERROR_STOP=0 2>&1 | tail -30
kubectl -n veritas exec "$POSTGRES_POD" -- psql -U veritas -c "SELECT count(*) AS tables FROM information_schema.tables WHERE table_schema='public';"

echo "=== BTCPay postgres restore ==="
kubectl -n btcpay wait --for=condition=ready pod -l app=postgres-btcpay --timeout=300s
BTCPAY_PG_POD=$(kubectl -n btcpay get pod -l app=postgres-btcpay -o jsonpath='{.items[0].metadata.name}')
echo "Restoring into $BTCPAY_PG_POD"
gunzip -c "$BACKUP_DIR/btcpay-pg_dumpall.sql.gz" | kubectl -n btcpay exec -i "$BTCPAY_PG_POD" -- psql -U btcpay -v ON_ERROR_STOP=0 2>&1 | tail -30

echo "=== WireGuard handoff ==="
cd "$REPO_ROOT"
docker compose stop veritas-agent 2>/dev/null || true
ip link del wg0 2>/dev/null || true
kubectl apply -k "$REPO_ROOT/deploy/k8s/overlays/prod/"
kubectl -n veritas scale deploy/auth-svc deploy/wg-manager deploy/billing-svc deploy/nginx deploy/veritas-proxy deploy/redis --replicas=1
kubectl -n veritas scale sts/nats --replicas=1 2>/dev/null || true

echo "=== Cloudflared ==="
set -a
# shellcheck disable=SC1091
source "$REPO_ROOT/.env"
set +a
TOKEN="${CLOUDFLARE_TUNNEL_TOKEN:-}"
if [ -z "$TOKEN" ]; then
  TOKEN=$(docker inspect veritasvpn-cloudflared-1 --format '{{json .Args}}' | python3 -c 'import json,sys; a=json.load(sys.stdin); print(a[a.index("--token")+1])')
fi
kubectl apply -f "$REPO_ROOT/deploy/k8s/ingress-nginx/cloudflared.yaml"
kubectl apply -f "$REPO_ROOT/deploy/k8s/ingress-nginx/network-policies.yaml"
kubectl -n ingress-nginx delete secret cloudflared-token --ignore-not-found
kubectl -n ingress-nginx create secret generic cloudflared-token --from-literal=token="$TOKEN"
kubectl -n ingress-nginx rollout restart deploy/cloudflared 2>/dev/null || true

echo "=== Stop compose ==="
docker compose -f "$REPO_ROOT/docker-compose.yml" down || true
docker compose -f "$REPO_ROOT/btcpay/docker-compose.yml" down || true

echo "=== Wait pods ==="
for i in $(seq 1 48); do
  echo "--- attempt $i ---"
  kubectl -n veritas get pods -o wide || true
  kubectl -n btcpay get pods -o wide || true
  kubectl -n ingress-nginx get pods -o wide || true
  ready=$(kubectl -n veritas get pods --no-headers 2>/dev/null | grep -c Running || true)
  echo "veritas Running=$ready"
  if [ "${ready:-0}" -ge 7 ]; then break; fi
  sleep 10
done

echo "=== Validation ==="
kubectl get nodes
ip link show wg0 || true
wg show || true
ss -lunu | grep 51820 || true
curl -sS -o /dev/null -w 'public:%{http_code}\n' --max-time 15 https://veritasvpn.cloud/healthz || true
kubectl -n veritas get deploy,sts,ds,svc,ingress -o wide || true
kubectl -n btcpay get deploy,sts,svc -o wide || true
kubectl -n ingress-nginx get deploy,svc -o wide || true
echo DONE
