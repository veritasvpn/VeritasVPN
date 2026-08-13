#!/usr/bin/env bash
set -euo pipefail

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

echo "[verify] WireGuard interface"
check "wg0 exists"            "ip link show wg0"
check "wg0 has address 10.0.0.1" "ip addr show wg0 | grep -q 10.0.0.1"
check "UDP 51820 listening"   "ss -lntu | grep -q 51820"

echo "[verify] forwarding"
check "IPv4 forwarding"       "sysctl -n net.ipv4.ip_forward | grep -q 1"

echo "[verify] firewall"
check "nftables table veritas" "nft list tables | grep -q veritas"

echo "[verify] docker"
check "docker running"        "docker info"
check "containers healthy"    "docker compose ps --format json | grep -v unhealthy"

echo "[verify] services"
check "nginx healthy"         "curl -sf http://localhost:80/healthz"
check "postgres reachable"    "docker compose exec -T postgres pg_isready -U veritas"

echo "[verify] peers"
WG_PEERS=$(wg show wg0 peers 2>/dev/null | wc -l)
echo "  Peers: $WG_PEERS"

if [ "$FAIL" -eq 1 ]; then
  echo "[verify] FAILURE — one or more checks failed"
  exit 1
else
  echo "[verify] all checks passed"
fi
