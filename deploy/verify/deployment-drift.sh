#!/usr/bin/env bash
set -euo pipefail

# Read-only deployment guard. It deliberately does not mutate the cluster or
# the working tree; it reports drift that should be reviewed before rollout.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

failures=0
check() {
  local label="$1"
  shift
  if "$@"; then
    printf '[OK] %s\n' "$label"
  else
    printf '[FAIL] %s\n' "$label" >&2
    failures=$((failures + 1))
  fi
}

# Root (or any other uid) running against a jpg-owned checkout must not spam
# "dubious ownership" / safe.directory into the journal, and must not treat a
# failed git invocation as a clean tree (empty stdout + test -z → false OK).
git_working_tree_clean() {
  local status
  status="$(git -c "safe.directory=$ROOT" status --porcelain 2>/dev/null)" || return 1
  test -z "$status"
}

# kustomize is local-only, but the k3s-wrapped kubectl still probes
# /etc/rancher/k3s/config.yaml (root-only) and warns on every call.
# Keep real errors; drop that known noise. Do not redirect check()'s stdout.
kustomize_renders() {
  local path="$1"
  local err
  set +e
  err="$(kubectl kustomize "$path" 2>&1 >/dev/null)"
  local rc=$?
  set -e
  if [ "$rc" -ne 0 ]; then
    printf '%s\n' "$err" >&2
    return "$rc"
  fi
  local filtered
  filtered="$(printf '%s\n' "$err" | grep -vF 'open /etc/rancher/k3s/config.yaml: permission denied' || true)"
  if [ -n "$filtered" ]; then
    printf '%s\n' "$filtered" >&2
  fi
  return 0
}

check "git working tree is clean" git_working_tree_clean
check "production kustomization renders" kustomize_renders deploy/k8s/overlays/k3s
check "monitoring kustomization renders" kustomize_renders deploy/k8s/monitoring
check "backup metrics directory is readable" test -r /var/lib/veritasvpn/metrics
check "backup metrics are scrapeable" test "$(curl -fsS --max-time 5 http://127.0.0.1:9100/metrics | grep -c '^veritas_backup_last_success_timestamp ' || true)" -ge 1
check "node exporter textfile collector is healthy" test "$(curl -fsS --max-time 5 http://127.0.0.1:9100/metrics | awk '/^node_textfile_scrape_error / {print $2; found=1} END {if (!found) print 1}')" = 0

if command -v promtool >/dev/null 2>&1; then
  check "Prometheus alert rules validate" promtool check rules deploy/k8s/monitoring/alerts.yml
else
  printf '[INFO] promtool not installed; alert-rule syntax check deferred to the Prometheus container.\n'
fi

if [ "$failures" -gt 0 ]; then
  printf '%s deployment checks failed.\n' "$failures" >&2
  exit 1
fi
printf 'Deployment checks passed.\n'
