#!/usr/bin/env bash
# Probe production public IP metrics ports from off-LAN. Expect fail (refused/timeout).
# Agent/node-exporter bind 0.0.0.0 for in-cluster scrape; uplink must drop 9090/9100.
set -euo pipefail

PUBLIC_IP="${PUBLIC_IP:-}"
TIMEOUT_SEC="${TIMEOUT_SEC:-5}"
PORTS="${METRICS_PORTS:-9090 9100}"

if [[ -z "$PUBLIC_IP" ]]; then
  printf 'PUBLIC_IP required (set VERITAS_PUBLIC_IP / egress address; do not use Cloudflare proxy A records)\n' >&2
  exit 2
fi

fail=0
for port in $PORTS; do
  printf 'Probing %s:%s (expect fail from WAN)\n' "$PUBLIC_IP" "$port"
  set +e
  timeout "$TIMEOUT_SEC" bash -c "echo >/dev/tcp/${PUBLIC_IP}/${port}" 2>/dev/null
  rc=$?
  set -e
  if (( rc == 0 )); then
    printf 'FAIL: TCP %s:%s accepted — metrics may be exposed on WAN\n' "$PUBLIC_IP" "$port" >&2
    fail=1
  else
    printf 'OK: %s:%s did not connect (rc=%s)\n' "$PUBLIC_IP" "$port" "$rc"
  fi
done

if (( fail != 0 )); then
  exit 1
fi
printf 'Metrics WAN check: PASS\n'
