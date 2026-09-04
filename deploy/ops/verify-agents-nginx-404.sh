#!/usr/bin/env bash
# Fail if compose/legacy nginx still proxy /api/v1/agents/ to wg-manager.
# Agents must use in-cluster MANAGER_ENDPOINT only (public edge returns 404).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
fail=0

check_file() {
  local f="$1"
  if [[ ! -f "$f" ]]; then
    printf 'missing %s\n' "$f" >&2
    fail=1
    return
  fi
  if awk '
    /location[[:space:]]+\/api\/v1\/agents\// { in_loc=1 }
    in_loc && /proxy_pass/ { found=1 }
    in_loc && /^[[:space:]]*}/ { if (found) exit 1; in_loc=0; found=0 }
    END { exit 0 }
  ' "$f"; then
    printf 'OK: %s agents location has no proxy_pass\n' "$f"
  else
    printf 'FAIL: %s still proxy_pass under /api/v1/agents/\n' "$f" >&2
    fail=1
  fi
  if ! grep -q 'return 404' "$f"; then
    # Soft check — location should 404
    if ! awk '/location[[:space:]]+\/api\/v1\/agents\//,/^[[:space:]]*}/ { if (/return 404/) found=1 } END { exit !found }' "$f"; then
      printf 'FAIL: %s agents location missing return 404\n' "$f" >&2
      fail=1
    fi
  fi
}

check_file "$ROOT/website/nginx.conf"
check_file "$ROOT/deploy/nginx/nginx.prod.conf"

# k8s configmap must also 404 (already production path)
if ! grep -A3 'location /api/v1/agents/' "$ROOT/deploy/k8s/base/nginx-configmap.yaml" | grep -q 'return 404'; then
  printf 'FAIL: k8s nginx-configmap agents location missing return 404\n' >&2
  fail=1
else
  printf 'OK: k8s nginx-configmap agents return 404\n'
fi

exit "$fail"
