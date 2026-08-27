#!/usr/bin/env bash
set -euo pipefail

FAIL=0
ok() { printf '[OK]   %s\n' "$1"; }
bad() { printf '[FAIL] %s\n' "$1" >&2; FAIL=1; }
warn() { printf '[WARN] %s\n' "$1"; }

if kubectl wait --for=condition=Ready node --all --timeout=10s >/dev/null 2>&1; then ok 'k3s node ready'; else bad 'k3s node is not ready'; fi

# Production namespaces only. Testnet (btcpay) is retired / optional.
for ns in veritas btcpay-mainnet monitoring ingress-nginx; do
  if kubectl get namespace "$ns" >/dev/null 2>&1; then ok "namespace $ns exists"; else bad "namespace $ns is missing"; fi
done
if kubectl get namespace btcpay >/dev/null 2>&1; then
  warn 'namespace btcpay still exists (testnet retired; ignore scaled-down resources)'
fi

# Ignore pods in retired testnet namespace when judging cluster health.
if kubectl get pods -A --field-selector=status.phase!=Running,status.phase!=Succeeded --no-headers 2>/dev/null \
  | awk '$1 != "btcpay" { print }' | grep -q .; then
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

# Mainnet Bitcoin IBD is payment-plane only. Never fail the VPN health timer for it.
bitcoin_mainnet_ready_replicas="$(kubectl -n btcpay-mainnet get deployment bitcoin-readiness-mainnet -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)"
if [[ "$bitcoin_mainnet_ready_replicas" == "1" ]]; then
  ok 'btcpay-mainnet/bitcoin-readiness-mainnet ready'
else
  warn 'btcpay-mainnet/bitcoin-readiness-mainnet is not ready; mainnet billing may be gated'
fi

check_http() {
  local label="$1" url="$2"
  if curl -fsS --max-time 10 "$url" >/dev/null 2>&1; then ok "$label"; else bad "$label"; fi
}
check_http 'public API health' 'https://api.veritasvpn.cloud/healthz'
billing_code="$(curl -sS --max-time 10 -o /dev/null -w "%{http_code}" https://api.veritasvpn.cloud/api/v1/billing/readyz || true)"
bitcoin_metrics=""
bitcoin_pf_log="$(mktemp)"
kubectl -n btcpay-mainnet port-forward svc/bitcoin-readiness-mainnet 18080:8080 >"$bitcoin_pf_log" 2>&1 &
bitcoin_pf_pid=$!
for _ in $(seq 1 25); do
  if bitcoin_metrics="$(curl -fsS --max-time 1 http://127.0.0.1:18080/metrics 2>/dev/null)"; then
    break
  fi
  sleep 0.2
done
kill "$bitcoin_pf_pid" 2>/dev/null || true
wait "$bitcoin_pf_pid" 2>/dev/null || true
rm -f "$bitcoin_pf_log"
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
  warn "public API billing readiness returned HTTP $billing_code (payment plane)"
fi
check_http 'public website' 'https://veritasvpn.cloud/'

# BTCPay public hostname is Cloudflare Access-gated — do not hard-fail on it.
# In-cluster mainnet service is the payment-plane health signal.
btcpay_code="$(curl -sS --max-time 5 -o /dev/null -w "%{http_code}" \
  http://btcpayserver-mainnet.btcpay-mainnet.svc.cluster.local:49392/ 2>/dev/null || true)"
if [[ "$btcpay_code" =~ ^(200|302|301|401|403)$ ]]; then
  ok "in-cluster BTCPay mainnet responds (HTTP $btcpay_code)"
else
  # From host Network, cluster DNS may be unreachable — fall back via kubectl.
  if kubectl -n btcpay-mainnet get deploy btcpayserver-mainnet -o jsonpath='{.status.readyReplicas}' 2>/dev/null | grep -q '^1$'; then
    ok 'BTCPay mainnet deployment ready (cluster DNS unreachable from host)'
  else
    bad 'BTCPay mainnet is not ready'
  fi
fi

# Stealth / wstunnel TCP 443 on the VPN node.
if curl -fsS --max-time 5 -o /dev/null https://127.0.0.1:443/ 2>/dev/null \
  || timeout 3 bash -c 'echo >/dev/tcp/127.0.0.1/443' 2>/dev/null; then
  ok 'stealth listener TCP 443 is open on localhost'
else
  bad 'stealth listener TCP 443 is not reachable on localhost'
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
  if kubectl -n btcpay-mainnet exec bitcoind-mainnet-0 -- sh -c 'bitcoin-cli -conf=/etc/bitcoin-rpc/bitcoin.conf getblockchaininfo >/dev/null 2>&1' >/dev/null 2>&1; then
    ok 'Bitcoin mainnet RPC responds'
  else
    warn 'Bitcoin mainnet RPC is unavailable (payment plane; not a VPN failure)'
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

# Host hardening checks (best-effort; missing tools become warnings).
if command -v nft >/dev/null 2>&1; then
  if nft list table inet veritas_filter >/dev/null 2>&1; then
    ok 'host firewall table veritas_filter present'
  else
    warn 'host firewall table veritas_filter missing'
  fi
  if nft list table inet veritas >/dev/null 2>&1; then
    ok 'agent firewall table veritas present'
  else
    warn 'agent firewall table veritas missing'
  fi
fi

FILTER_TABLE=""
if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  FILTER_TABLE="$(nft list table inet veritas_filter 2>/dev/null || true)"
  if grep -Fq '9090' <<<"$FILTER_TABLE"; then
    ok 'host firewall protects agent metrics on uplink'
  else
    warn 'host firewall metrics protection not detected'
  fi
  if grep -Fq 'iifname "tailscale0" tcp dport' <<<"$FILTER_TABLE" \
    && grep -Eq 'iifname "tailscale0" counter.*drop' <<<"$FILTER_TABLE"; then
    ok 'Tailnet host access is explicitly allowlisted'
  else
    bad 'Tailnet host access is not explicitly restricted'
  fi
else
  warn 'skipping host firewall/tailnet checks (need root)'
fi
if curl -fsS --max-time 2 http://127.0.0.1:9090/healthz >/dev/null 2>&1; then
  ok 'agent /healthz responds on localhost'
else
  warn 'agent /healthz not reachable on localhost'
fi

BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/veritasvpn}"
if [[ ! -r "$BACKUP_ROOT" ]]; then
  warn "backup root not readable ($BACKUP_ROOT); skip age check"
else
  latest="$(find "$BACKUP_ROOT" -maxdepth 1 -type f -name 'veritasvpn-*.tar.gz.enc' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -1 | cut -d' ' -f2- || true)"
  if [[ -n "$latest" ]]; then
    age=$(( ( $(date +%s) - $(stat -c %Y "$latest") ) / 3600 ))
    if (( age <= 25 )); then ok "encrypted backup age ${age}h"; else bad "encrypted backup is ${age}h old"; fi
  else
    bad 'no encrypted backup found'
  fi
fi

if (( FAIL )); then
  printf 'Kubernetes health: FAILURE\n' >&2
  exit 1
fi
printf 'Kubernetes health: PASS\n'
