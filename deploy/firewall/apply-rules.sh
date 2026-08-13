#!/usr/bin/env bash
set -euo pipefail

RULES_FILE="$(dirname "$0")/nftables.conf"
ROLLBACK_MINUTES=2

if [ "$(id -u)" -ne 0 ]; then
  echo "Must be run as root"
  exit 1
fi

echo "[firewall] applying nftables rules from $RULES_FILE"
echo "[firewall] scheduling rollback in $ROLLBACK_MINUTES minutes"
echo "[firewall] press Ctrl+C AFTER testing to cancel rollback and persist rules"

nft -f "$RULES_FILE"
echo "[firewall] rules applied"

(
  sleep $((ROLLBACK_MINUTES * 60))
  echo ""
  echo "[firewall] ROLLBACK TIMER EXPIRED — restoring previous rules"
  nft flush ruleset
  echo "[firewall] nftables rules flushed — system reverted to ACCEPT all"
) &

ROLLBACK_PID=$!

echo "[firewall] rollback PID: $ROLLBACK_PID"
echo "[firewall] === open a NEW ssh session via tailscale and test connectivity ==="
echo "[firewall] === test external WireGuard connection ==="
echo "[firewall] === if all tests pass, run: kill $ROLLBACK_PID ==="
echo "[firewall] === then persist with: ./persist-rules.sh ==="

wait $ROLLBACK_PID 2>/dev/null || true
