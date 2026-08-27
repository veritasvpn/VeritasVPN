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

check "git working tree is clean" test -z "$(git status --porcelain)"
check "production kustomization renders" kubectl kustomize deploy/k8s/overlays/k3s >/dev/null
check "monitoring kustomization renders" kubectl kustomize deploy/k8s/monitoring >/dev/null
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
