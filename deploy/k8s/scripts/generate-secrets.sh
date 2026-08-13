#!/usr/bin/env bash
# Generate deploy/k8s/{base,btcpay}/secrets.yaml from live .env + running containers.
# Does NOT print secret values. Safe to re-run (overwrites secrets.yaml only).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
ENV_FILE="${ENV_FILE:-$REPO_ROOT/.env}"
OUT_BASE="$REPO_ROOT/deploy/k8s/base/secrets.yaml"
OUT_BTCPAY="$REPO_ROOT/deploy/k8s/btcpay/secrets.yaml"

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: $ENV_FILE not found" >&2
  exit 1
fi

# shellcheck disable=SC1090
set -a
source "$ENV_FILE"
set +a

need() {
  local k="$1"
  if [ -z "${!k:-}" ]; then
    echo "ERROR: required env var $k is empty/unset in $ENV_FILE" >&2
    exit 1
  fi
}

need DB_PASSWORD
need JWT_SECRET
need AGENT_AUTH_TOKEN

REDIS_PASSWORD="${REDIS_PASSWORD:-$DB_PASSWORD}"
NATS_USER="${NATS_USER:-veritas}"
NATS_PASSWORD="${NATS_PASSWORD:-$DB_PASSWORD}"
RESEND_API_KEY="${RESEND_API_KEY:-}"
BTCPAY_API_KEY="${BTCPAY_API_KEY:-}"
BTCPAY_STORE_ID="${BTCPAY_STORE_ID:-}"
BTCPAY_WEBHOOK_SECRET="${BTCPAY_WEBHOOK_SECRET:-}"

# Prefer live BTCPay container secrets when available (avoids re-sync).
BTCPAY_POSTGRES_PASSWORD="${BTCPAY_POSTGRES_PASSWORD:-}"
if [ -z "$BTCPAY_POSTGRES_PASSWORD" ]; then
  BTCPAY_POSTGRES_PASSWORD="$(docker inspect btcpay-postgres-btcpay-1 \
    --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | awk -F= '/^POSTGRES_PASSWORD=/{print substr($0,19); exit}' || true)"
fi
if [ -z "$BTCPAY_POSTGRES_PASSWORD" ]; then
  BTCPAY_POSTGRES_PASSWORD="$(openssl rand -hex 16)"
  echo "WARN: generated new BTCPAY_POSTGRES_PASSWORD (compose value not found)"
fi

BTC_RPC_PASSWORD="${BTC_RPC_PASSWORD:-}"
if [ -z "$BTC_RPC_PASSWORD" ]; then
  # BITCOIN_EXTRA_ARGS embeds rpcpassword=...
  BTC_RPC_PASSWORD="$(docker inspect btcpay-bitcoind-1 \
    --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | awk -F= '/^BITCOIN_EXTRA_ARGS=/{print; exit}' \
    | tr '\n' ' ' \
    | sed -n 's/.*rpcpassword=\([^[:space:]]*\).*/\1/p' || true)"
fi
if [ -z "$BTC_RPC_PASSWORD" ]; then
  BTC_RPC_PASSWORD="$(openssl rand -hex 16)"
  echo "WARN: generated new BTC_RPC_PASSWORD (compose value not found)"
fi
BTC_RPC_USER="${BTC_RPC_USER:-btcpay_rpc}"

umask 077
cat > "$OUT_BASE" <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: veritas-secrets
  namespace: veritas
type: Opaque
stringData:
  DB_PASSWORD: $(printf '%s' "$DB_PASSWORD" | sed 's/"/\\"/g')
  JWT_SECRET: $(printf '%s' "$JWT_SECRET" | sed 's/"/\\"/g')
  AGENT_AUTH_TOKEN: $(printf '%s' "$AGENT_AUTH_TOKEN" | sed 's/"/\\"/g')
  REDIS_PASSWORD: $(printf '%s' "$REDIS_PASSWORD" | sed 's/"/\\"/g')
  NATS_USER: $(printf '%s' "$NATS_USER" | sed 's/"/\\"/g')
  NATS_PASSWORD: $(printf '%s' "$NATS_PASSWORD" | sed 's/"/\\"/g')
  RESEND_API_KEY: $(printf '%s' "$RESEND_API_KEY" | sed 's/"/\\"/g')
  BTCPAY_API_KEY: $(printf '%s' "$BTCPAY_API_KEY" | sed 's/"/\\"/g')
  BTCPAY_STORE_ID: $(printf '%s' "$BTCPAY_STORE_ID" | sed 's/"/\\"/g')
  BTCPAY_WEBHOOK_SECRET: $(printf '%s' "$BTCPAY_WEBHOOK_SECRET" | sed 's/"/\\"/g')
EOF

cat > "$OUT_BTCPAY" <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: btcpay-secrets
  namespace: btcpay
type: Opaque
stringData:
  BTCPAY_POSTGRES_PASSWORD: $(printf '%s' "$BTCPAY_POSTGRES_PASSWORD" | sed 's/"/\\"/g')
  BTC_RPC_PASSWORD: $(printf '%s' "$BTC_RPC_PASSWORD" | sed 's/"/\\"/g')
  BTC_RPC_USER: $(printf '%s' "$BTC_RPC_USER" | sed 's/"/\\"/g')
EOF

chmod 600 "$OUT_BASE" "$OUT_BTCPAY"

# Validate YAML keys exist without printing values
# Inject REDIS_URL (URL-encoded password) without relying on k8s env expansion
python3 - <<'PY2' "$OUT_BASE"
import pathlib, re, urllib.parse, sys
p = pathlib.Path(sys.argv[1])
text = p.read_text()
# parse REDIS_PASSWORD from yaml stringData
m = re.search(r'REDIS_PASSWORD:\s*(.+)', text)
if not m:
    raise SystemExit('REDIS_PASSWORD missing')
pw = m.group(1).strip()
url = 'redis://:' + urllib.parse.quote(pw, safe='') + '@redis:6379/0'
if 'REDIS_URL:' in text:
    text = re.sub(r'REDIS_URL:\s*.*', 'REDIS_URL: ' + url, text)
else:
    text = text.replace('  REDIS_PASSWORD: ' + pw, '  REDIS_PASSWORD: ' + pw + '\n  REDIS_URL: ' + url)
p.write_text(text)
print('REDIS_URL injected')
PY2

python3 - <<'PY' "$OUT_BASE" "$OUT_BTCPAY"
import sys
try:
    import yaml
except ImportError:
    # minimal check: files non-empty and contain required keys
    for path, keys in [
        (sys.argv[1], ["DB_PASSWORD", "JWT_SECRET", "AGENT_AUTH_TOKEN", "REDIS_PASSWORD"]),
        (sys.argv[2], ["BTCPAY_POSTGRES_PASSWORD", "BTC_RPC_PASSWORD", "BTC_RPC_USER"]),
    ]:
        text = open(path).read()
        for k in keys:
            if f"{k}:" not in text:
                raise SystemExit(f"missing key {k} in {path}")
    print("secrets files written (basic key check OK)")
    raise SystemExit(0)

for path, keys in [
    (sys.argv[1], ["DB_PASSWORD", "JWT_SECRET", "AGENT_AUTH_TOKEN", "REDIS_PASSWORD", "NATS_USER", "NATS_PASSWORD"]),
    (sys.argv[2], ["BTCPAY_POSTGRES_PASSWORD", "BTC_RPC_PASSWORD", "BTC_RPC_USER"]),
]:
    doc = yaml.safe_load(open(path))
    sd = doc.get("stringData") or {}
    for k in keys:
        if not sd.get(k):
            raise SystemExit(f"empty/missing key {k} in {path}")
print("secrets files written and validated")
PY

echo "Wrote: $OUT_BASE"
echo "Wrote: $OUT_BTCPAY"
echo "(files are gitignored; mode 600)"
