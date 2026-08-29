#!/usr/bin/env bash
set -euo pipefail

FAIL=0
ok() { printf '[OK]   %s\n' "$1"; }
bad() { printf '[FAIL] %s\n' "$1" >&2; FAIL=1; }
warn() { printf '[WARN] %s\n' "$1"; }

if kubectl wait --for=condition=Ready node --all --timeout=30s >/dev/null 2>&1; then ok 'K3s node ready'; else bad 'K3s node not ready'; fi
for namespace in veritas monitoring btcpay-mainnet ingress-nginx; do
  if kubectl get namespace "$namespace" >/dev/null 2>&1; then ok "namespace/$namespace exists"; else bad "namespace/$namespace missing"; fi
done

if kubectl get pods -A --field-selector=status.phase!=Running,status.phase!=Succeeded --no-headers 2>/dev/null \
  | awk '$1 != "btcpay" {print}' | grep -q .; then
  bad 'one or more production pods are not running'
else
  ok 'all production pods are running or completed'
fi

if ip link show wg0 >/dev/null 2>&1 && wg show wg0 >/dev/null 2>&1; then ok 'WireGuard interface wg0 available'; else bad 'WireGuard interface wg0 missing'; fi
if ss -H -lun | awk '{print $5}' | grep -Eq '(^|:)51820$'; then ok 'WireGuard UDP 51820 listening'; else bad 'WireGuard UDP 51820 not listening'; fi
if [[ "$(sysctl -n net.ipv4.ip_forward 2>/dev/null)" == '1' ]]; then ok 'IPv4 forwarding enabled'; else bad 'IPv4 forwarding disabled'; fi
if nft list table inet veritas_filter >/dev/null 2>&1 && nft list table inet veritas >/dev/null 2>&1; then ok 'host and VPN firewall tables loaded'; else bad 'required nftables tables missing'; fi

if dig +short +time=3 +tries=1 @10.0.0.1 api.veritasvpn.cloud A | grep -Eq '^[0-9]+(\.[0-9]+){3}$'; then ok 'VPN DNS forwarder resolves'; else bad 'VPN DNS forwarder failed'; fi
if curl -fsS --max-time 10 https://api.veritasvpn.cloud/healthz >/dev/null; then ok 'public API healthy'; else bad 'public API unavailable'; fi
if curl -fsS --max-time 10 https://veritasvpn.cloud/ >/dev/null; then ok 'public website healthy'; else bad 'public website unavailable'; fi
if timeout 5 bash -c 'echo >/dev/tcp/170.51.31.139/41080' 2>/dev/null; then ok 'Chrome proxy port reachable'; else bad 'Chrome proxy port unavailable'; fi
if timeout 5 bash -c 'echo >/dev/tcp/127.0.0.1/443' 2>/dev/null; then ok 'stealth TCP listener reachable'; else bad 'stealth TCP listener unavailable'; fi

configured="$(wg show wg0 peers 2>/dev/null | wc -l | tr -d ' ')"
if [[ "$configured" == '0' ]]; then warn 'no client peers currently configured; scheduled external E2E is the connectivity proof'; else ok "$configured WireGuard peer(s) configured"; fi

if (( FAIL )); then
  printf 'Boot verification: FAILURE\n' >&2
  exit 1
fi
printf 'Boot verification: PASS\n'
