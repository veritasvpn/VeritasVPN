#!/usr/bin/env bash
# Verify veritas-agent ↔ wg-manager health (heartbeat auth + public WG port).
# Run on Dell with kubectl, or any host that can reach the cluster.
set -euo pipefail

NS="${NS:-veritas}"
export KUBECONFIG="${KUBECONFIG:-${HOME}/.kube/config}"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "OK: $*"; }

command -v kubectl >/dev/null || fail "kubectl required"

AG_JSON="$(kubectl -n "$NS" get pod -l app=veritas-agent -o json 2>/dev/null || true)"
[[ -n "$AG_JSON" ]] || fail "no veritas-agent pod"
AG="$(kubectl -n "$NS" get pod -l app=veritas-agent -o jsonpath='{.items[0].metadata.name}')"
PHASE="$(kubectl -n "$NS" get pod "$AG" -o jsonpath='{.status.phase}')"
[[ "$PHASE" == "Running" ]] || fail "agent pod not Running ($PHASE)"

IMAGE_ID="$(kubectl -n "$NS" get pod "$AG" -o jsonpath='{.status.containerStatuses[0].imageID}')"
ok "agent pod=$AG imageID=$IMAGE_ID"

# Heartbeat must not 401 in the last 2 minutes (allow one startup race).
LOGS="$(kubectl -n "$NS" logs "$AG" --since=2m 2>/dev/null || true)"
if echo "$LOGS" | grep -q 'Heartbeat failed'; then
  if echo "$LOGS" | grep -q 'heartbeat returned 401'; then
    fail "agent heartbeat returning 401 — rebuild/redeploy veritas-agent (Bearer + WG_PUBLIC_PORT required)"
  fi
  echo "WARN: recent Heartbeat failed (non-401); inspect logs" >&2
else
  ok "no heartbeat failures in last 2m"
fi

# Online servers must advertise public UDP port 443 (router DNAT), not listen-only 51820.
ROWS="$(kubectl -n "$NS" exec postgres-0 -- psql -U veritas -d veritas -Atc \
  "SELECT hostname||':'||wg_port||':'||status FROM servers WHERE status='online';" 2>/dev/null || true)"
[[ -n "$ROWS" ]] || fail "no online servers in DB"
while IFS= read -r row; do
  [[ -z "$row" ]] && continue
  host="${row%%:*}"
  rest="${row#*:}"
  port="${rest%%:*}"
  status="${rest##*:}"
  if [[ "$port" != "443" ]]; then
    fail "online server $host advertises wg_port=$port (want 443 for WAN clients); check WG_PUBLIC_PORT + agent image"
  fi
  ok "online server $host wg_port=$port status=$status"
done <<< "$ROWS"

ok "agent health checks passed"
