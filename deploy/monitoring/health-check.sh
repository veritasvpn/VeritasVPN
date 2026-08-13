#!/usr/bin/env bash
set -euo pipefail

echo "=== VeritasVPN Health Monitor $(date -Iseconds) ==="
FAIL=0

check_cmd() {
  local desc="$1"
  local cmd="$2"
  if eval "$cmd" &>/dev/null; then
    echo "  [OK] $desc"
  else
    echo "  [FAIL] $desc"
    FAIL=1
  fi
}

check_http() {
  local desc="$1"
  local url="$2"
  local code="${3:-200}"
  local actual
  actual=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$url" 2>/dev/null || echo "000")
  if [ "$actual" = "$code" ]; then
    echo "  [OK] $desc ($code)"
  else
    echo "  [FAIL] $desc (expected $code, got $actual)"
    FAIL=1
  fi
}

echo "--- host ---"
check_cmd "CPU load < 2"          "[ \$(awk '{print \$1}' /proc/loadavg | cut -d. -f1) -lt 2 ]"
check_cmd "memory available"      "[ \$(free -m | awk '/^Mem:/{print \$7}') -gt 50 ]"
check_cmd "disk usage < 90%"      "[ \$(df / | awk 'NR==2{print \$5}' | tr -d '%') -lt 90 ]"
check_cmd "temperature < 80C"     "command -v vcgencmd && vcgencmd measure_temp | grep -oP '[0-9]+' | awk '\$1<80000{exit 1}' || true"

echo "--- vpn ---"
check_cmd "wg0 interface"         "ip link show wg0"
check_cmd "UDP 51820 listener"    "ss -lntu | grep -q 51820"
WG_AGE=$(wg show wg0 latest-handshakes 2>/dev/null | awk '{print $2}' | sort -n | tail -1)
if [ -n "$WG_AGE" ] && [ "$WG_AGE" -lt 300 ]; then
  echo "  [OK] peer handshake < 5min"
else
  echo "  [FAIL] stale peer handshake"
  FAIL=1
fi
check_cmd "nftables table veritas" "nft list tables | grep -q veritas"

echo "--- docker ---"
check_cmd "docker running"       "docker info"
check_cmd "containers all running" "test \$(docker compose ps --format json 2>/dev/null | grep -c running) -ge 8"

echo "--- services ---"
check_http "nginx healthz"       "http://localhost:80/healthz" 200
check_http "auth-svc healthz"    "http://localhost:8080/healthz" 200
check_http "wg-manager healthz"  "http://localhost:8080/healthz" 200
check_http "billing healthz"     "http://localhost:8080/healthz" 200

echo "--- database ---"
check_cmd "postgres reachable"  "docker compose exec -T postgres pg_isready -U veritas"

echo "--- backups ---"
LATEST_BACKUP=$(find ./backups/postgres/daily -type f -name '*.sql.gz*' 2>/dev/null | sort -r | head -1)
if [ -n "$LATEST_BACKUP" ]; then
  BACKUP_AGE=$(( ($(date +%s) - $(stat -c %Y "$LATEST_BACKUP")) / 3600 ))
  if [ "$BACKUP_AGE" -lt 25 ]; then
    echo "  [OK] backup age: ${BACKUP_AGE}h"
  else
    echo "  [FAIL] backup age: ${BACKUP_AGE}h (> 24h)"
    FAIL=1
  fi
else
  echo "  [FAIL] no backups found"
  FAIL=1
fi

echo ""
if [ "$FAIL" -eq 0 ]; then
  echo "[HEALTH] all checks passed"
else
  echo "[HEALTH] FAILURE — one or more checks failed"
fi
exit $FAIL
