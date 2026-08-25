#!/usr/bin/env bash
set -euo pipefail

FAIL=0
ok() { printf '[OK]   %s\n' "$1"; }
bad() { printf '[FAIL] %s\n' "$1" >&2; FAIL=1; }
warn() { printf '[WARN] %s\n' "$1"; }

if kubectl wait --for=condition=Ready node --all --timeout=10s >/dev/null 2>&1; then ok 'k3s node ready'; else bad 'k3s node is not ready'; fi

# Active payment stack is btcpay-mainnet; keep btcpay namespace optional (scaled-down testnet).
for ns in veritas btcpay-mainnet monitoring ingress-nginx; do
  if kubectl get namespace "$ns" >/dev/null 2>&1; then ok "namespace $ns exists"; else bad "namespace $ns is missing"; fi
done
if kubectl get namespace btcpay >/dev/null 2>&1; then
  warn 'namespace btcpay exists (legacy testnet; expected scaled to zero)'
fi

if kubectl get pods -A --field-selector=status.phase!=Running,status.phase!=Succeeded --no-headers 2>/dev/null | grep -q .; then
  bad 'one or more pods are not running or completed successfully'
else
  ok 'all pods are running or completed successfully'
fi

for target in \
  'deployment/auth-svc:veritas' \
  'deployment/wg-manager:veritas' \
  'deployment/billing-svc:veritas' \
  'statefulset/bitcoind-mainnet:btcpay-mainnet' \
  'statefulset/nbxplorer-mainnet:btcpay-mainnet' \
  'deployment/btcpayserver-mainnet:btcpay-mainnet' \
  'deployment/prometheus:monitoring' \
  'deployment/grafana:monitoring'; do
  resource=${target%%:*}; namespace=${target##*:}
  if kubectl -n "$namespace" rollout status "$resource" --timeout=10s >/dev/null 2>&1; then
    ok "$namespace/$resource ready"
  else
    bad "$namespace/$resource is not ready"
  fi
done

bitcoin_ready_replicas="$(kubectl -n btcpay-mainnet get deployment bitcoin-readiness-mainnet -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)"
if [[ "$bitcoin_ready_replicas" == "1" ]]; then
  ok 'btcpay-mainnet/bitcoin-readiness-mainnet ready'
else
  warn 'btcpay-mainnet/bitcoin-readiness-mainnet is not ready; Bitcoin payments remain gated'
fi

check_http() {
  local label="$1" url="$2"
  if curl -fsS --max-time 10 "$url" >/dev/null 2>&1; then ok "$label"; else bad "$label"; fi
}
check_http 'public API health' 'https://api.veritasvpn.cloud/healthz'
billing_code="$(curl -sS --max-time 10 -o /dev/null -w "%{http_code}" https://api.veritasvpn.cloud/api/v1/billing/readyz || true)"
bitcoin_metrics="$(kubectl -n btcpay-mainnet exec deploy/bitcoin-readiness-mainnet -- wget -qO- http://127.0.0.1:8080/metrics 2>/dev/null || true)"
bitcoin_ibd="$(awk '$1 == "bitcoin_initial_block_download" { print $2 }' <<<"$bitcoin_metrics")"
if [[ "$billing_code" == "200" ]]; then
  if [[ "$bitcoin_ibd" == "1" ]]; then
    bad 'public API billing readiness is incorrectly healthy while Bitcoin is synchronizing'
  else
    ok 'public API billing readiness'
  fi
elif [[ "$billing_code" == "503" && "$bitcoin_ibd" == "1" ]]; then
  warn 'Bitcoin payments intentionally gated while node synchronizes'
elif [[ "$billing_code" == "000" ]]; then
  bad 'public API billing readiness is unreachable'
else
  bad "public API billing readiness returned HTTP $billing_code"
fi
check_http 'public website' 'https://veritasvpn.cloud/'
# Mainnet public UI may be behind Cloudflare Access (302 to login) — treat 2xx/3xx as up.
btcpay_code="$(curl -sS --max-time 10 -o /dev/null -w "%{http_code}" https://btcpay-mainnet.veritasvpn.cloud/ || true)"
if [[ "$btcpay_code" =~ ^(2|3)[0-9][0-9]$ ]]; then
  ok "public BTCPay mainnet (HTTP $btcpay_code)"
else
  bad "public BTCPay mainnet returned HTTP ${btcpay_code:-000}"
fi

dns_http_code="$(curl -sS --max-time 5 -o /dev/null -w "%{http_code}" https://cloudflare-dns.com/dns-query || true)"
if [[ "$dns_http_code" != "000" ]]; then
  ok "encrypted DNS upstream reachable (HTTP $dns_http_code)"
else
  bad "encrypted DNS upstream unavailable"
fi
if command -v dig >/dev/null 2>&1 && dig +short +time=3 +tries=1 @10.0.0.1 api.veritasvpn.cloud A | grep -Eq "^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$"; then
  ok "VPN DNS forwarder resolves api.veritasvpn.cloud"
else
  bad "VPN DNS forwarder failed to resolve api.veritasvpn.cloud"
fi

if kubectl -n btcpay-mainnet get pod bitcoind-mainnet-0 >/dev/null 2>&1; then
  if kubectl -n btcpay-mainnet exec bitcoind-mainnet-0 -- bitcoin-cli -conf=/etc/bitcoin-rpc/bitcoin.conf getblockchaininfo >/dev/null 2>&1; then
    ok 'Bitcoin RPC responds (mainnet)'
  else
    bad 'Bitcoin RPC is unavailable (mainnet)'
  fi
fi

# WireGuard lives in the hostNetwork veritas-agent pod on k3s nodes.
if kubectl -n veritas exec ds/veritas-agent -- wg show wg0 >/dev/null 2>&1; then
  peers="$(kubectl -n veritas exec ds/veritas-agent -- wg show wg0 latest-handshakes 2>/dev/null | awk '$2 > 0 { count++ } END { print count+0 }')"
  configured="$(kubectl -n veritas exec ds/veritas-agent -- wg show wg0 peers 2>/dev/null | wc -l | tr -d ' ')"
  if [ "$configured" -eq 0 ] || [ "$peers" -gt 0 ]; then
    ok "WireGuard interface healthy ($configured configured peers)"
  else
    bad 'WireGuard peers have no recent handshakes'
  fi
elif command -v wg >/dev/null 2>&1 && wg show wg0 >/dev/null 2>&1; then
  ok 'WireGuard interface present on host'
else
  warn 'WireGuard interface is not present on this host'
fi

# Prefer node_exporter textfile metrics written by backup-k3s.sh (covers R2 offsite).
backup_ts="$(curl -fsS --max-time 5 http://127.0.0.1:9100/metrics 2>/dev/null | awk '/^veritas_backup_offsite_last_success_timestamp / {print $2; exit}')"
backup_ts_int="${backup_ts%.*}"
if [[ -z "$backup_ts_int" || "$backup_ts_int" -eq 0 ]] 2>/dev/null; then
  backup_ts="$(curl -fsS --max-time 5 http://127.0.0.1:9100/metrics 2>/dev/null | awk '/^veritas_backup_last_success_timestamp / {print $2; exit}')"
  backup_ts_int="${backup_ts%.*}"
fi
if [[ -n "$backup_ts_int" ]] && [[ "$backup_ts_int" =~ ^[0-9]+$ ]] && (( backup_ts_int > 0 )); then
  now="$(date +%s)"
  age=$(( (now - backup_ts_int) / 3600 ))
  if (( age < 0 )); then age=0; fi
  if (( age <= 36 )); then ok "encrypted backup age ${age}h (metrics)"; else bad "encrypted backup is ${age}h old (metrics)"; fi
else
  BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/veritasvpn}"
  latest="$(find "$BACKUP_ROOT" -maxdepth 1 -type f -name 'veritasvpn-*.tar.gz.enc' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -1 | cut -d' ' -f2- || true)"
  if [[ -n "$latest" ]]; then
    age=$(( ( $(date +%s) - $(stat -c %Y "$latest") ) / 3600 ))
    if (( age <= 25 )); then ok "encrypted backup age ${age}h (local)"; else bad "encrypted backup is ${age}h old (local)"; fi
  else
    bad 'no encrypted backup found (metrics empty and local archive missing)'
  fi
fi

if (( FAIL )); then
  printf 'Kubernetes health: FAILURE\n' >&2
  exit 1
fi
printf 'Kubernetes health: PASS\n'
