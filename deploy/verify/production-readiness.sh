#!/usr/bin/env bash
set -euo pipefail

# Read-only preflight for the production Kubernetes overlay.  This intentionally
# does not contact the cluster or print secret values; it catches configuration
# drift before an operator runs the deploy/cutover scripts.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OVERLAY="$ROOT_DIR/deploy/k8s/overlays/prod"
FAIL=0

pass() { printf '[OK]   %s\n' "$1"; }
fail() { printf '[FAIL] %s\n' "$1" >&2; FAIL=1; }

require_file() {
  if [[ -f "$1" ]]; then pass "$2"; else fail "$2 (missing: $1)"; fi
}

require_text() {
  local pattern="$1" file="$2" description="$3"
  if grep -Eq -- "$pattern" "$file"; then pass "$description"; else fail "$description"; fi
}

printf 'VeritasVPN production readiness\nRoot: %s\n\n' "$ROOT_DIR"

require_file "$OVERLAY/kustomization.yaml" 'production overlay exists'
require_file "$ROOT_DIR/deploy/k8s/btcpay/bitcoind.yaml" 'Bitcoin node manifest exists'
require_file "$ROOT_DIR/deploy/backup/backup-k3s.sh" 'encrypted cluster backup script exists'
require_file "$ROOT_DIR/deploy/monitoring/health-check.sh" 'health monitor exists'

require_text 'SERVER_COUNTRY' "$OVERLAY/kustomization.yaml" 'production server location is declared'
require_text 'PUBLIC_IP' "$OVERLAY/kustomization.yaml" 'production public endpoint is declared'
require_text 'imagePullPolicy: IfNotPresent' "$ROOT_DIR/deploy/k8s/base/veritas-agent.yaml" 'agent avoids unnecessary image pulls'
require_text 'startupProbe:' "$ROOT_DIR/deploy/k8s/btcpay/bitcoind.yaml" 'Bitcoin startup probe is configured'
require_text 'txindex=0' "$ROOT_DIR/deploy/k8s/btcpay/bitcoind.yaml" 'Bitcoin node is configured for pruned-compatible operation'
require_text 'Strict-Transport-Security' "$ROOT_DIR/deploy/nginx/nginx.prod.conf" 'API edge sends HSTS'

# Never allow generated credentials or local secret manifests into Git.
if git -C "$ROOT_DIR" grep -Il -E \
    '(BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|cfut_[A-Za-z0-9])' -- \
    . ':(exclude)*.example' ':(exclude)*.example.*' ':(exclude)*.md' ':(exclude)*.lock' \
    >/tmp/veritasvpn-secret-scan 2>/dev/null; then
  fail 'tracked files contain credential-like material (see /tmp/veritasvpn-secret-scan)'
else
  pass 'no credential-like material detected in tracked files'
fi

if command -v kubectl >/dev/null 2>&1; then
  if kubectl kustomize "$OVERLAY" >/dev/null 2>&1; then
    pass 'production manifests render with kubectl kustomize'
  elif [[ ! -f "$ROOT_DIR/deploy/k8s/base/secrets.yaml" ]]; then
    # The generated files are intentionally absent from Git. On a live host,
    # the equivalent secrets must already exist in the cluster.
    if kubectl -n veritas get secret veritas-secrets >/dev/null 2>&1 && \
       kubectl -n btcpay get secret btcpay-secrets >/dev/null 2>&1; then
      pass 'live cluster secrets exist (generated manifests remain untracked)'
    else
      fail 'production manifests require generated secrets or live veritas-secrets/btcpay-secrets'
    fi
  else
    fail 'production manifests do not render with kubectl kustomize'
  fi
else
  printf '[SKIP] kubectl not installed; render check deferred to deployment host\n'
fi

if (( FAIL )); then
  printf '\nReadiness: BLOCKED\n' >&2
  exit 1
fi
printf '\nReadiness: PASS (live cluster, DNS, payment settlement, and secret checks still require deployment-host validation)\n'
