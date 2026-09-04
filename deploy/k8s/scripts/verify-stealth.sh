#!/usr/bin/env bash
# Verify VeritasVPN stealth (wstunnel on TCP 443) is live on a k3s node.
set -euo pipefail

NAMESPACE="${NAMESPACE:-veritas}"
PUBLIC_HOST="${PUBLIC_HOST:-api.veritasvpn.cloud}"
PUBLIC_PORT="${PUBLIC_PORT:-443}"
KUBECONFIG="${KUBECONFIG:-${HOME}/.kube/config}"
export KUBECONFIG

fail() { printf '[FAIL] %s\n' "$1" >&2; exit 1; }
ok() { printf '[OK]   %s\n' "$1"; }

enabled="$(kubectl -n "$NAMESPACE" get configmap veritas-config -o jsonpath='{.data.STEALTH_ENABLED}')"
host="$(kubectl -n "$NAMESPACE" get configmap veritas-config -o jsonpath='{.data.STEALTH_ENDPOINT_HOST}')"
port="$(kubectl -n "$NAMESPACE" get configmap veritas-config -o jsonpath='{.data.STEALTH_ENDPOINT_PORT}')"
prefix_len="$(kubectl -n "$NAMESPACE" get secret veritas-secrets -o jsonpath='{.data.STEALTH_PATH_PREFIX}' | base64 -d | wc -c | tr -d ' ')"

[[ "$enabled" == "true" || "$enabled" == "1" ]] || fail "STEALTH_ENABLED is not true (got: ${enabled:-empty})"
[[ -n "$host" ]] || fail "STEALTH_ENDPOINT_HOST is empty"
[[ "$port" == "$PUBLIC_PORT" ]] || fail "STEALTH_ENDPOINT_PORT is ${port:-empty}, expected $PUBLIC_PORT"
[[ "${prefix_len:-0}" -ge 16 ]] || fail "STEALTH_PATH_PREFIX missing or too short"
ok "wg-manager stealth config enabled ($host:$port)"

desired="$(kubectl -n "$NAMESPACE" get daemonset veritas-wstunnel -o jsonpath='{.status.desiredNumberScheduled}')"
ready="$(kubectl -n "$NAMESPACE" get daemonset veritas-wstunnel -o jsonpath='{.status.numberReady}')"
[[ "$desired" != "0" && "$ready" == "$desired" ]] || fail "daemonset/veritas-wstunnel not ready ($ready/$desired)"
ok "daemonset/veritas-wstunnel ready ($ready/$desired)"

timeout 3 bash -c "echo >/dev/tcp/127.0.0.1/${PUBLIC_PORT}" 2>/dev/null \
  || fail "nothing listening on 127.0.0.1:${PUBLIC_PORT}"
ok "local TCP ${PUBLIC_PORT} accepts connections"

if timeout 5 bash -c "echo >/dev/tcp/${PUBLIC_HOST}/${PUBLIC_PORT}" 2>/dev/null; then
  ok "public ${PUBLIC_HOST}:${PUBLIC_PORT} reachable from this host"
else
  printf '[WARN] public %s:%s not reachable from this host (hairpin NAT or ISP block is common)\n' "$PUBLIC_HOST" "$PUBLIC_PORT"
  printf '       Test from cellular: nc -zv %s %s\n' "$PUBLIC_HOST" "$PUBLIC_PORT"
fi

printf '\nStealth verification: PASS (server ready for Linux desktop Settings → Stealth mode)\n'
