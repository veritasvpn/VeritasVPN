#!/usr/bin/env bash
set -euo pipefail

# Read-only health audit for the single-node k3s production deployment.
FAIL=0
ok() { printf '[OK]   %s\n' "$1"; }
bad() { printf '[FAIL] %s\n' "$1" >&2; FAIL=1; }

if kubectl wait --for=condition=Ready node --all --timeout=10s >/dev/null 2>&1; then
  ok 'k3s node ready'
else
  bad 'k3s node is not ready'
fi

for ns in veritas btcpay monitoring ingress-nginx; do
  if kubectl get namespace "$ns" >/dev/null 2>&1; then
    ok "namespace $ns exists"
  else
    bad "namespace $ns is missing"
  fi
done

if kubectl get pods -A --field-selector=status.phase!=Running,status.phase!=Succeeded \
    --no-headers 2>/dev/null | grep -q .; then
  bad 'one or more pods are not running or completed successfully'
else
  ok 'all pods are running or completed successfully'
fi

check_http() {
  local label="$1" url="$2"
  if curl -fsS --max-time 10 "$url" >/dev/null 2>&1; then ok "$label"; else bad "$label"; fi
}

check_http 'public API health' 'https://api.veritasvpn.cloud/healthz'
check_http 'public website' 'https://veritasvpn.cloud/'

BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/veritasvpn}"
latest="$(find "$BACKUP_ROOT" -maxdepth 1 -type f -name 'veritasvpn-*.tar.gz.enc' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -1 | cut -d' ' -f2-)"
if [[ -n "$latest" ]]; then
  age=$(( ( $(date +%s) - $(stat -c %Y "$latest") ) / 3600 ))
  if (( age <= 25 )); then ok "encrypted backup age ${age}h"; else bad "encrypted backup is ${age}h old"; fi
else
  bad 'no encrypted backup found'
fi

if (( FAIL )); then
  printf 'Kubernetes health: FAILURE\n' >&2
  exit 1
fi
printf 'Kubernetes health: PASS\n'
