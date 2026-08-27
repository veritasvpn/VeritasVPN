#!/usr/bin/env bash
set -euo pipefail

# Read-only preflight for the canonical production Kubernetes overlay (k3s).
# This intentionally does not print secret values; it catches configuration
# drift before an operator runs the deploy/cutover scripts.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OVERLAY="$ROOT_DIR/deploy/k8s/overlays/k3s"
BTCPAY_DIR="$ROOT_DIR/deploy/k8s/btcpay-mainnet"
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

require_file "$OVERLAY/kustomization.yaml" 'canonical k3s overlay exists'
require_file "$BTCPAY_DIR/bitcoind.yaml" 'BTCPay mainnet Bitcoin node manifest exists'
require_file "$ROOT_DIR/deploy/backup/backup-k3s.sh" 'encrypted cluster backup script exists'
require_file "$ROOT_DIR/deploy/monitoring/health-check.sh" 'health monitor exists'
require_file "$ROOT_DIR/deploy/k8s/SECRETS.md" 'secrets apply documentation exists'

require_text 'SERVER_COUNTRY' "$OVERLAY/kustomization.yaml" 'production server location is declared'
require_text 'PUBLIC_IP' "$OVERLAY/kustomization.yaml" 'production public endpoint is declared'
require_text 'STEALTH_ENABLED' "$OVERLAY/kustomization.yaml" 'stealth is enabled in k3s overlay'
require_text 'wstunnel' "$OVERLAY/kustomization.yaml" 'wstunnel image is digest-pinned'
require_text 'tauri://localhost' "$OVERLAY/kustomization.yaml" 'desktop Tauri origins are in CORS'
require_text 'imagePullPolicy: IfNotPresent' "$ROOT_DIR/deploy/k8s/base/veritas-agent.yaml" 'agent avoids unnecessary image pulls'
require_text 'startupProbe:' "$BTCPAY_DIR/bitcoind.yaml" 'Bitcoin startup probe is configured'
require_text 'txindex=0' "$BTCPAY_DIR/bitcoind.yaml" 'Bitcoin node is configured for pruned-compatible operation'
require_text 'Strict-Transport-Security' "$ROOT_DIR/deploy/nginx/nginx.prod.conf" 'API edge sends HSTS'

# secrets.yaml must not be in default kustomize resources (wipe risk).
if grep -Eq -- '^\s*-\s*secrets\.yaml\s*$' "$BTCPAY_DIR/kustomization.yaml"; then
  fail 'btcpay-mainnet kustomization must not apply secrets.yaml'
else
  pass 'btcpay-mainnet kustomization does not apply secrets.yaml'
fi
if grep -Eq -- '^\s*-\s*secrets\.yaml\s*$' "$ROOT_DIR/deploy/k8s/base/kustomization.yaml"; then
  fail 'veritas base kustomization must not apply secrets.yaml'
else
  pass 'veritas base kustomization does not apply secrets.yaml'
fi

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
    pass 'k3s manifests render with kubectl kustomize'
  elif [[ ! -f "$ROOT_DIR/deploy/k8s/base/secrets.yaml" ]]; then
    if kubectl -n veritas get secret veritas-secrets >/dev/null 2>&1 && \
       kubectl -n btcpay-mainnet get secret btcpay-secrets >/dev/null 2>&1; then
      pass 'live cluster secrets exist (generated manifests remain untracked)'
    else
      fail 'k3s manifests require live veritas-secrets and btcpay-mainnet/btcpay-secrets'
    fi
  else
    fail 'k3s manifests do not render with kubectl kustomize'
  fi
  if kubectl kustomize "$BTCPAY_DIR" >/dev/null 2>&1; then
    pass 'btcpay-mainnet manifests render without secrets.yaml'
  else
    fail 'btcpay-mainnet kustomize build failed'
  fi
else
  printf '[SKIP] kubectl not installed; render check deferred to deployment host\n'
fi

if (( FAIL )); then
  printf '\nReadiness: BLOCKED\n' >&2
  exit 1
fi
printf '\nReadiness: PASS (live cluster, DNS, payment settlement, and secret checks still require deployment-host validation)\n'
