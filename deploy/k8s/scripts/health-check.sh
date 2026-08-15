#!/usr/bin/env bash
set -euo pipefail

FAIL=0
ok() { printf '[OK]   %s\n' "$1"; }
bad() { printf '[FAIL] %s\n' "$1" >&2; FAIL=1; }
warn() { printf '[WARN] %s\n' "$1"; }

if kubectl wait --for=condition=Ready node --all --timeout=10s >/dev/null 2>&1; then ok 'k3s node ready'; else bad 'k3s node is not ready'; fi
for ns in veritas btcpay monitoring ingress-nginx; do
  if kubectl get namespace "$ns" >/dev/null 2>&1; then ok "namespace $ns exists"; else bad "namespace $ns is missing"; fi
done
if kubectl get pods -A --field-selector=status.phase!=Running,status.phase!=Succeeded --no-headers 2>/dev/null | grep -q .; then
  bad 'one or more pods are not running or completed successfully'
else
  ok 'all pods are running or completed successfully'
fi

for target in \
  'deployment/auth-svc:veritas' \
  'deployment/wg-manager:veritas' \
  'deployment/billing-svc:veritas' \
  'statefulset/bitcoind:btcpay' \
  'statefulset/nbxplorer:btcpay' \
  'deployment/btcpayserver:btcpay' \
  'deployment/prometheus:monitoring' \
  'deployment/grafana:monitoring'; do
  resource=${target%%:*}; namespace=${target##*:}
  if kubectl -n "$namespace" rollout status "$resource" --timeout=10s >/dev/null 2>&1; then
    ok "$namespace/$resource ready"
  else
    bad "$namespace/$resource is not ready"
  fi
done

check_http() {
  local label="$1" url="$2"
  if curl -fsS --max-time 10 "$url" >/dev/null 2>&1; then ok "$label"; else bad "$label"; fi
}
check_http 'public API health' 'https://api.veritasvpn.cloud/healthz'
check_http 'public API billing readiness' 'https://api.veritasvpn.cloud/api/v1/billing/readyz'
check_http 'public website' 'https://veritasvpn.cloud/'
check_http 'public BTCPay' 'https://btcpay.veritasvpn.cloud/'

dns_http_code="$(curl -sS --max-time 5 -o /dev/null -w "%{http_code}" https://cloudflare-dns.com/dns-query || true)"
if [[ "$dns_http_code" != "000" ]]; then
  ok "encrypted DNS upstream reachable (HTTP $dns_http_code)"
else
  bad "encrypted DNS upstream unavailable"
fi

if kubectl -n btcpay get pod bitcoind-0 >/dev/null 2>&1; then
  if kubectl -n btcpay exec bitcoind-0 -- sh -c 'bitcoin-cli -rpcuser="$BTC_RPC_USER" -rpcpassword="$BTC_RPC_PASSWORD" getblockchaininfo >/dev/null 2>&1' >/dev/null 2>&1; then
    ok 'Bitcoin RPC responds'
  else
    bad 'Bitcoin RPC is unavailable'
  fi
fi

if command -v wg >/dev/null 2>&1 && wg show wg0 >/dev/null 2>&1; then
  peers="$(wg show wg0 latest-handshakes | awk '$2 > 0 { count++ } END { print count+0 }')"
  configured="$(wg show wg0 peers | wc -l | tr -d ' ')"
  if [ "$configured" -eq 0 ] || [ "$peers" -gt 0 ]; then
    ok "WireGuard interface healthy ($configured configured peers)"
  else
    bad 'WireGuard peers have no recent handshakes'
  fi
else
  warn 'WireGuard interface is not present on this host'
fi

BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/veritasvpn}"
latest="$(find "$BACKUP_ROOT" -maxdepth 1 -type f -name 'veritasvpn-*.tar.gz.enc' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -1 | cut -d' ' -f2- || true)"
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
