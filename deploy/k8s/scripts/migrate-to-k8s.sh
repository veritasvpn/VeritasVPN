#!/usr/bin/env bash
# Non-interactive Compose → k3s production cutover for this host.
# Usage:
#   sudo -E bash deploy/k8s/scripts/migrate-to-k8s.sh
# Optional:
#   SKIP_BACKUP=1 SKIP_BTCPAY_DATA=1 bash ...
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: run as root: sudo -E bash $0"
  exit 1
fi

export REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MIGRATION_TS="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="${BACKUP_DIR:-$REPO_ROOT/backups/pre-k3s-$MIGRATION_TS}"
OVERLAY="${OVERLAY:-prod}"
LOG="$REPO_ROOT/backups/migrate-to-k8s-$MIGRATION_TS.log"
mkdir -p "$(dirname "$LOG")" "$BACKUP_DIR"
exec > >(tee -a "$LOG") 2>&1

cd "$REPO_ROOT"

echo "============================================"
echo "  VeritasVPN: Compose → k3s (production)"
echo "  Repo:    $REPO_ROOT"
echo "  Overlay: $OVERLAY"
echo "  Backup:  $BACKUP_DIR"
echo "  Log:     $LOG"
echo "============================================"

step() { echo ""; echo "=== STEP: $* ==="; echo ""; }

# ---------- 1. Preflight ----------
step "Preflight"
command -v docker >/dev/null
docker compose ps --format 'table {{.Name}}\t{{.Status}}' || true
ss -lnt | grep -E ':(6443|10250)\s' && echo "WARN: k3s ports already in use" || echo "k3s ports free"
test -f "$REPO_ROOT/.env"
test -f "$REPO_ROOT/data/wireguard/private.key"
chmod 600 "$REPO_ROOT/data/wireguard/private.key" || true
chown root:root "$REPO_ROOT/data/wireguard/private.key" || true

# ---------- 2. Backup ----------
if [ "${SKIP_BACKUP:-0}" != "1" ]; then
  step "Backup"
  cp -a "$REPO_ROOT/data/wireguard" "$BACKUP_DIR/wireguard"
  install -m 600 "$REPO_ROOT/.env" "$BACKUP_DIR/.env"
  docker compose -f docker-compose.yml config > "$BACKUP_DIR/compose-config.yml" || true
  docker compose -f btcpay/docker-compose.yml config > "$BACKUP_DIR/btcpay-compose-config.yml" 2>/dev/null || true
  echo "Dumping veritas postgres..."
  docker compose exec -T postgres pg_dumpall -U veritas | gzip > "$BACKUP_DIR/veritas-pg_dumpall.sql.gz"
  echo "Dumping btcpay postgres..."
  docker compose -f btcpay/docker-compose.yml exec -T postgres-btcpay pg_dumpall -U btcpay 2>/dev/null \
    | gzip > "$BACKUP_DIR/btcpay-pg_dumpall.sql.gz" || echo "WARN: btcpay dump failed"
  docker images --format '{{.Repository}}:{{.Tag}} {{.ID}}' > "$BACKUP_DIR/docker-images.txt"
  wg show > "$BACKUP_DIR/wg-show.txt" 2>/dev/null || true
  ls -lh "$BACKUP_DIR"
else
  step "Backup SKIPPED"
fi

# ---------- 3. Generate secrets ----------
step "Generate K8s secrets from live .env"
# run as the repo owner so files land with correct ownership later
if [ -n "${SUDO_USER:-}" ]; then
  sudo -u "$SUDO_USER" bash "$SCRIPT_DIR/generate-secrets.sh"
else
  bash "$SCRIPT_DIR/generate-secrets.sh"
fi
test -f "$REPO_ROOT/deploy/k8s/base/secrets.yaml"
test -f "$REPO_ROOT/deploy/k8s/btcpay/secrets.yaml"

# ---------- 4. Install k3s ----------
step "Install k3s (if needed)"
if systemctl is-active --quiet k3s 2>/dev/null; then
  echo "k3s already running"
else
  curl -sfL https://get.k3s.io | sh -s - \
    --write-kubeconfig-mode 644 \
    --disable servicelb \
    --disable traefik \
    --kubelet-arg=max-pods=250
  sleep 5
fi
# kubeconfig for root + invoking user
mkdir -p /root/.kube
cp /etc/rancher/k3s/k3s.yaml /root/.kube/config
chmod 600 /root/.kube/config
if [ -n "${SUDO_USER:-}" ]; then
  USER_HOME="$(getent passwd "$SUDO_USER" | cut -d: -f6)"
  mkdir -p "$USER_HOME/.kube"
  cp /etc/rancher/k3s/k3s.yaml "$USER_HOME/.kube/config"
  chown -R "$SUDO_USER:$SUDO_USER" "$USER_HOME/.kube"
  chmod 600 "$USER_HOME/.kube/config"
fi
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
kubectl wait --for=condition=ready node --all --timeout=180s
kubectl get nodes -o wide

# Label VPN node
NODE_NAME="$(kubectl get nodes -o jsonpath='{.items[0].metadata.name}')"
kubectl label node "$NODE_NAME" veritas-vpn-node=true --overwrite
kubectl label node "$NODE_NAME" kubernetes.io/arch- 2>/dev/null || true

# ---------- 5. Import images ----------
step "Import docker images into k3s"
bash "$SCRIPT_DIR/import-images.sh"

# ---------- 6. Ingress-nginx ----------
step "Install ingress-nginx"
if ! kubectl -n ingress-nginx get deploy ingress-nginx-controller >/dev/null 2>&1; then
  kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.2/deploy/static/provider/baremetal/deploy.yaml
fi
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=180s || echo "WARN: ingress-nginx not ready yet"

# ---------- 7. Cloudflared secret ----------
step "Cloudflared tunnel secret"
# shellcheck disable=SC1091
set -a
source "$REPO_ROOT/.env"
set +a
TOKEN="${CLOUDFLARE_TUNNEL_TOKEN:-}"
if [ -z "$TOKEN" ]; then
  # fall back to token from running compose container args
  TOKEN="$(docker inspect veritasvpn-cloudflared-1 --format '{{json .Args}}' 2>/dev/null \
    | python3 -c 'import json,sys; a=json.load(sys.stdin); print(a[a.index("--token")+1] if "--token" in a else "")' 2>/dev/null || true)"
fi
if [ -z "$TOKEN" ]; then
  echo "ERROR: CLOUDFLARE_TUNNEL_TOKEN not found"
  exit 1
fi
kubectl create namespace ingress-nginx --dry-run=client -o yaml | kubectl apply -f -
kubectl -n ingress-nginx delete secret cloudflared-token --ignore-not-found
kubectl -n ingress-nginx create secret generic cloudflared-token --from-literal=token="$TOKEN"

# ---------- 8. Apply BTCPay manifests first (long-running nodes) ----------
step "Apply BTCPay stack"
# bump btcpayserver image to match running compose
sed -i 's|btcpayserver/btcpayserver:2.1.3|btcpayserver/btcpayserver:2.3.9|' \
  "$REPO_ROOT/deploy/k8s/btcpay/btcpayserver.yaml"
kubectl apply -k "$REPO_ROOT/deploy/k8s/btcpay/"

# ---------- 9. Postgres migration (veritas) ----------
step "Migrate Veritas PostgreSQL"
# Apply full overlay (includes postgres)
kubectl apply -k "$REPO_ROOT/deploy/k8s/overlays/${OVERLAY}/"

# Scale down app workloads while we restore DB
kubectl -n veritas scale deploy/auth-svc deploy/wg-manager deploy/billing-svc deploy/nginx deploy/veritas-proxy deploy/redis --replicas=0 2>/dev/null || true
kubectl -n veritas delete ds veritas-agent --ignore-not-found 2>/dev/null || true
kubectl -n veritas scale sts/nats --replicas=0 2>/dev/null || true

kubectl -n veritas wait --for=condition=ready pod -l app=postgres --timeout=180s
POSTGRES_POD="$(kubectl -n veritas get pod -l app=postgres -o jsonpath='{.items[0].metadata.name}')"

echo "Restoring veritas dump into $POSTGRES_POD ..."
# Drop default empty DB objects that conflict, then restore
gunzip -c "$BACKUP_DIR/veritas-pg_dumpall.sql.gz" \
  | kubectl -n veritas exec -i "$POSTGRES_POD" -- psql -U veritas -v ON_ERROR_STOP=0 2>&1 \
  | tail -20

kubectl -n veritas exec "$POSTGRES_POD" -- psql -U veritas -c \
  "SELECT count(*) AS tables FROM information_schema.tables WHERE table_schema='public';"

# ---------- 10. BTCPay postgres restore ----------
step "Migrate BTCPay PostgreSQL"
kubectl -n btcpay wait --for=condition=ready pod -l app=postgres-btcpay --timeout=180s || \
  kubectl -n btcpay wait --for=condition=ready pod -l app=postgres --timeout=180s || true
BTCPAY_PG_POD="$(kubectl -n btcpay get pod -o name 2>/dev/null | grep postgres | head -1 | cut -d/ -f2 || true)"
if [ -n "$BTCPAY_PG_POD" ] && [ -f "$BACKUP_DIR/btcpay-pg_dumpall.sql.gz" ]; then
  echo "Restoring btcpay dump into $BTCPAY_PG_POD ..."
  gunzip -c "$BACKUP_DIR/btcpay-pg_dumpall.sql.gz" \
    | kubectl -n btcpay exec -i "$BTCPAY_PG_POD" -- psql -U btcpay -v ON_ERROR_STOP=0 2>&1 \
    | tail -20
else
  echo "WARN: skipping btcpay postgres restore (pod or dump missing)"
fi

# ---------- 11. WireGuard handoff ----------
step "WireGuard handoff"
docker compose stop veritas-agent 2>/dev/null || true
ip link del wg0 2>/dev/null || true
# Re-apply full stack (agent + scaled services)
kubectl apply -k "$REPO_ROOT/deploy/k8s/overlays/${OVERLAY}/"
kubectl -n veritas scale deploy/auth-svc deploy/wg-manager deploy/billing-svc deploy/nginx deploy/veritas-proxy deploy/redis --replicas=1
kubectl -n veritas scale sts/nats --replicas=1 2>/dev/null || true

# ---------- 12. Cloudflared + cutover ----------
step "Cut over cloudflared + stop compose"
# Prefer newer cloudflared image matching compose
sed -i 's|cloudflare/cloudflared:2025.6.1|cloudflare/cloudflared:2026.7.3|' \
  "$REPO_ROOT/deploy/k8s/ingress-nginx/cloudflared.yaml" || true
# Ensure secret is used (strip empty inline token secret from manifest if present by applying deploy only)
kubectl apply -f "$REPO_ROOT/deploy/k8s/ingress-nginx/cloudflared.yaml" || true
# Re-assert secret after apply (manifest may contain empty secret)
kubectl -n ingress-nginx delete secret cloudflared-token --ignore-not-found
kubectl -n ingress-nginx create secret generic cloudflared-token --from-literal=token="$TOKEN"
kubectl -n ingress-nginx rollout restart deploy/cloudflared 2>/dev/null || true

echo "Stopping compose stacks..."
docker compose -f "$REPO_ROOT/docker-compose.yml" down || true
docker compose -f "$REPO_ROOT/btcpay/docker-compose.yml" down || true

# ---------- 13. Wait + validate ----------
step "Wait for workloads"
sleep 10
kubectl -n veritas get pods -o wide || true
kubectl -n btcpay get pods -o wide || true
kubectl -n ingress-nginx get pods -o wide || true

for i in $(seq 1 36); do
  ready="$(kubectl -n veritas get pods --no-headers 2>/dev/null | grep -c 'Running' || true)"
  echo "  veritas Running pods: $ready (attempt $i)"
  if [ "${ready:-0}" -ge 7 ]; then break; fi
  sleep 10
done

step "Validation"
FAIL=0
check() {
  local d="$1" c="$2"
  if eval "$c" >/dev/null 2>&1; then echo "  [OK] $d"; else echo "  [FAIL] $d"; FAIL=1; fi
}
check "node Ready" "kubectl get nodes | grep -q Ready"
check "wg0 up" "ip link show wg0"
check "UDP 51820" "ss -lunu | grep -q 51820"
check "postgres ready" "kubectl -n veritas exec sts/postgres -- pg_isready -U veritas"
# health via cluster or nodeport
INGRESS_IP="$(kubectl -n ingress-nginx get svc ingress-nginx-controller -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)"
check "ingress controller" "kubectl -n ingress-nginx get pods -l app.kubernetes.io/component=controller | grep -q Running"
check "cloudflared" "kubectl -n ingress-nginx get pods -l app=cloudflared | grep -q Running"
PUBLIC_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 https://veritasvpn.cloud/healthz || echo 000)"
echo "  public https://veritasvpn.cloud/healthz -> $PUBLIC_CODE"
[ "$PUBLIC_CODE" = "200" ] || FAIL=1

echo ""
kubectl -n veritas get deploy,sts,ds,svc,ingress -o wide || true
kubectl -n btcpay get deploy,sts,svc -o wide || true

if [ "$FAIL" -eq 0 ]; then
  echo ""
  echo "============================================"
  echo "  MIGRATION COMPLETE — cluster is primary"
  echo "============================================"
  echo "Next:"
  echo "  kubectl -n veritas get pods -o wide"
  echo "  curl -sS https://veritasvpn.cloud/healthz"
  echo "  wg show"
  echo "  bash deploy/monitoring/health-check.sh"
  echo "Rollback if needed:"
  echo "  sudo bash deploy/k8s/scripts/migration/rollback.sh"
  exit 0
else
  echo ""
  echo "============================================"
  echo "  MIGRATION FINISHED WITH FAILURES"
  echo "  See log: $LOG"
  echo "  Rollback: sudo bash deploy/k8s/scripts/migration/rollback.sh"
  echo "============================================"
  exit 1
fi
