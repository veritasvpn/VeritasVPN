#!/usr/bin/env bash
set -euo pipefail

echo "=== STEP 5: Create K8s secrets ==="
echo ""

REPO_ROOT="${REPO_ROOT:-/opt/veritasvpn}"
confirm() { read -rp "$1 [y/N] " yn; if [ "$yn" != "y" ] && [ "$yn" != "Y" ]; then echo "aborted"; exit 0; fi; }

if [ ! -f "$REPO_ROOT/.env" ]; then
  echo "ERROR: .env not found"
  exit 1
fi

source "$REPO_ROOT/.env"

# Create secrets.yaml from example
echo "Creating veritas secrets..."
cp "$REPO_ROOT/deploy/k8s/base/secrets.example.yaml" "$REPO_ROOT/deploy/k8s/base/secrets.yaml"
sed -i "s/CHANGE_ME/$DB_PASSWORD/" "$REPO_ROOT/deploy/k8s/base/secrets.yaml"
# JWT_SECRET is on its own line
sed -i "0,/CHANGE_ME/s//$JWT_SECRET/" "$REPO_ROOT/deploy/k8s/base/secrets.yaml"
# AGENT_AUTH_TOKEN
sed -i "0,/CHANGE_ME/s//${AGENT_AUTH_TOKEN}/" "$REPO_ROOT/deploy/k8s/base/secrets.yaml"
# REDIS_PASSWORD — use DB_PASSWORD as default if not set
REDIS_PASS="${REDIS_PASSWORD:-$DB_PASSWORD}"
sed -i "0,/CHANGE_ME/s//$REDIS_PASS/" "$REPO_ROOT/deploy/k8s/base/secrets.yaml"
# NATS credentials
NATS_U="${NATS_USER:-veritas}"
NATS_P="${NATS_PASSWORD:-$DB_PASSWORD}"
sed -i "0,/CHANGE_ME/s//$NATS_U/" "$REPO_ROOT/deploy/k8s/base/secrets.yaml"
sed -i "0,/CHANGE_ME/s//$NATS_P/" "$REPO_ROOT/deploy/k8s/base/secrets.yaml"

# Create BTCPay secrets
echo "Creating btcpay secrets..."
cp "$REPO_ROOT/deploy/k8s/btcpay/secrets.example.yaml" "$REPO_ROOT/deploy/k8s/btcpay/secrets.yaml"
BTC_RPC_PASS="${BTC_RPC_PASSWORD:-$(openssl rand -hex 16)}"
sed -i "0,/CHANGE_ME/s//$BTC_RPC_PASS/" "$REPO_ROOT/deploy/k8s/btcpay/secrets.yaml"
sed -i "0,/CHANGE_ME/s//$BTC_RPC_PASS/" "$REPO_ROOT/deploy/k8s/btcpay/secrets.yaml"
sed -i "0,/CHANGE_ME/s//btcpay_rpc/" "$REPO_ROOT/deploy/k8s/btcpay/secrets.yaml"
sed -i "0,/CHANGE_ME/s//btcpay_rpc/" "$REPO_ROOT/deploy/k8s/btcpay/secrets.yaml"

# Create cloudflared token secret
if [ -n "${CLOUDFLARE_TUNNEL_TOKEN:-}" ]; then
  echo "Creating cloudflared token secret..."
  kubectl create namespace ingress-nginx --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n ingress-nginx delete secret cloudflared-token --ignore-not-found
  kubectl -n ingress-nginx create secret generic cloudflared-token --from-literal=token="$CLOUDFLARE_TUNNEL_TOKEN"
fi

echo ""
echo "[secrets] Done."
echo "Verify: kubectl -n veritas get secret veritas-secrets -o jsonpath='{.data}' | head"
