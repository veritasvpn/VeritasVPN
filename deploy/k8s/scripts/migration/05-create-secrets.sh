#!/usr/bin/env bash
set -euo pipefail

echo "=== STEP 5: Create K8s secrets ==="
echo ""

REPO_ROOT="${REPO_ROOT:-/opt/veritasvpn}"

if [ ! -f "$REPO_ROOT/.env" ]; then
  echo "ERROR: .env not found"
  exit 1
fi

echo "Generating secrets.yaml from .env (Ed25519 JWT required)..."
ENV_FILE="$REPO_ROOT/.env" "$REPO_ROOT/deploy/k8s/scripts/generate-secrets.sh"

# Create cloudflared token secret
# shellcheck disable=SC1090
source "$REPO_ROOT/.env"
if [ -n "${CLOUDFLARE_TUNNEL_TOKEN:-}" ]; then
  echo "Creating cloudflared token secret..."
  kubectl create namespace ingress-nginx --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n ingress-nginx delete secret cloudflared-token --ignore-not-found
  kubectl -n ingress-nginx create secret generic cloudflared-token --from-literal=token="$CLOUDFLARE_TUNNEL_TOKEN"
fi

echo ""
echo "[secrets] Done. Apply with kubectl/create carefully — see deploy/k8s/SECRETS.md."
echo "Verify: kubectl -n veritas get secret veritas-secrets -o jsonpath='{.data}' | head"
