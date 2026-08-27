#!/usr/bin/env bash
set -euo pipefail

CLUSTER_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OVERLAY="${1:-k3s}"

case "$OVERLAY" in
  base|"")
    echo "Refusing to apply base alone — use overlays/k3s (production) or overlays/dev." >&2
    exit 1
    ;;
  prod)
    echo "WARNING: overlays/prod is a legacy alias kept in sync with k3s. Prefer: $0 k3s" >&2
    ;;
  k3s|dev) ;;
  *)
    echo "Unknown overlay '$OVERLAY'. Use: k3s | dev (prod is legacy alias)." >&2
    exit 1
    ;;
esac

echo "Applying VeritasVPN overlay: ${OVERLAY}"
kubectl apply -k "${CLUSTER_DIR}/overlays/${OVERLAY}"

echo ""
echo "Waiting for deployments to be ready..."
kubectl wait --for=condition=available --timeout=120s \
  deployment/auth-svc \
  deployment/billing-svc \
  deployment/wg-manager \
  deployment/redis \
  -n veritas 2>/dev/null || true

kubectl wait --for=condition=ready --timeout=120s \
  pod -l app=postgres \
  -n veritas 2>/dev/null || true

kubectl wait --for=condition=ready --timeout=120s \
  pod -l app=nats \
  -n veritas 2>/dev/null || true

kubectl wait --for=condition=available --timeout=120s \
  deployment/nginx \
  deployment/veritas-proxy \
  -n veritas 2>/dev/null || true

echo ""
echo "VeritasVPN base infrastructure status:"
kubectl get all -n veritas
