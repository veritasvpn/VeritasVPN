#!/usr/bin/env bash
set -euo pipefail

echo "=== STEP 9: Validation ==="
echo ""

REPO_ROOT="${REPO_ROOT:-/opt/veritasvpn}"
FAIL=0

check() {
  local desc="$1"
  local cmd="$2"
  if eval "$cmd" &>/dev/null; then
    echo "  [OK] $desc"
  else
    echo "  [FAIL] $desc"
    FAIL=1
  fi
}

echo "--- K8s Cluster ---"
check "node Ready"               "kubectl get nodes | grep -q Ready"

echo "--- WireGuard ---"
check "wg0 exists"               "ip link show wg0"
check "UDP 51820 listening"      "ss -lntu | grep -q 51820"

echo "--- Pods ---"
kubectl -n veritas get pods -o wide
RUNNING=$(kubectl -n veritas get pods --no-headers 2>/dev/null | grep -c Running || echo 0)
echo "  Running pods: $RUNNING"

echo "--- External API ---"
check "nginx healthz"            "curl -sf --max-time 10 http://localhost:8000/healthz"
check "auth healthz"             "curl -sf --max-time 10 http://localhost:8080/healthz"

echo "--- PostgreSQL ---"
check "postgres"                 "kubectl -n veritas exec deploy/postgres -- pg_isready -U veritas"

echo "--- Cloudflared ---"
check "cloudflared"              "kubectl -n ingress-nginx get pods -l app=cloudflared | grep -q Running"

echo "--- Public endpoint ---"
PUBLIC_CHECK=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 https://veritasvpn.cloud/healthz 2>/dev/null || echo "000")
if [ "$PUBLIC_CHECK" = "200" ]; then
  echo "  [OK] veritasvpn.cloud returns $PUBLIC_CHECK"
else
  echo "  [FAIL] veritasvpn.cloud returns $PUBLIC_CHECK"
  FAIL=1
fi

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "[validate] ALL CHECKS PASSED"
else
  echo "[validate] FAILURES DETECTED"
fi
exit $FAIL
