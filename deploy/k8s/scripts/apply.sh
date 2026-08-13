#!/usr/bin/env bash
set -euo pipefail

CLUSTER_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OVERLAY="${1:-dev}"

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
