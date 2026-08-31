#!/usr/bin/env bash
# Probe public WAN SSH on the production node. Expect connection refused / timeout
# from an off-LAN network. LAN/Tailscale SSH must still work (not tested here).
set -euo pipefail

PUBLIC_IP="${PUBLIC_IP:-}"
PORT="${SSH_PORT:-22}"
TIMEOUT_SEC="${TIMEOUT_SEC:-5}"

if [[ -z "$PUBLIC_IP" ]]; then
  # Historical Dell public (update if DNS A for api/stealth host changes).
  PUBLIC_IP="$(dig +short api.veritasvpn.cloud A 2>/dev/null | head -1 || true)"
fi
if [[ -z "$PUBLIC_IP" ]]; then
  printf 'PUBLIC_IP required (or dig api.veritasvpn.cloud)\n' >&2
  exit 2
fi

printf 'Probing %s:%s (expect fail from WAN)\n' "$PUBLIC_IP" "$PORT"
set +e
out="$(timeout "$TIMEOUT_SEC" bash -c "echo >/dev/tcp/${PUBLIC_IP}/${PORT}" 2>&1)"
rc=$?
set -e

if (( rc == 0 )); then
  printf 'FAIL: TCP %s:%s accepted from this network — WAN SSH may be exposed\n' \
    "$PUBLIC_IP" "$PORT" >&2
  exit 1
fi
printf 'OK: WAN SSH probe did not connect (rc=%s)\n' "$rc"
printf 'SSH WAN check: PASS\n'
