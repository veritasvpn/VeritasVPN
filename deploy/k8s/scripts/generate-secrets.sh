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
need JWT_ED25519_PRIVATE_KEY
need JWT_ED25519_PUBLIC_KEYS
need JWT_ACTIVE_KEY_ID
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
python3 - <<'PY' "$OUT_BASE" \
  "$DB_PASSWORD" "$JWT_ED25519_PRIVATE_KEY" "$JWT_ED25519_PUBLIC_KEYS" "$JWT_ACTIVE_KEY_ID" \
  "$AGENT_AUTH_TOKEN" "$REDIS_PASSWORD" "$NATS_USER" "$NATS_PASSWORD" \
  "$RESEND_API_KEY" "$BTCPAY_API_KEY" "$BTCPAY_STORE_ID" "$BTCPAY_WEBHOOK_SECRET"
import pathlib, sys, urllib.parse

out = pathlib.Path(sys.argv[1])
(
    db_password, jwt_private, jwt_public, jwt_kid, agent, redis_pw, nats_user, nats_pw,
    resend, btcpay_key, btcpay_store, btcpay_wh,
) = sys.argv[2:]

def q(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'

private = jwt_private.replace("\\n", "\n")
if "\n" in private:
    indented = "\n".join(("    " + line) if line else "" for line in private.rstrip("\n").splitlines())
    private_yaml = "|-\n" + indented
else:
    private_yaml = q(private)

redis_url = "redis://:" + urllib.parse.quote(redis_pw, safe="") + "@redis:6379/0"
out.write_text(f"""apiVersion: v1
kind: Secret
metadata:
  name: veritas-secrets
  namespace: veritas
type: Opaque
stringData:
  DB_PASSWORD: {q(db_password)}
  JWT_ED25519_PRIVATE_KEY: {private_yaml}
  JWT_ED25519_PUBLIC_KEYS: {q(jwt_public)}
  JWT_ACTIVE_KEY_ID: {q(jwt_kid)}
  AGENT_AUTH_TOKEN: {q(agent)}
  REDIS_PASSWORD: {q(redis_pw)}
  REDIS_URL: {q(redis_url)}
  NATS_USER: {q(nats_user)}
  NATS_PASSWORD: {q(nats_pw)}
  RESEND_API_KEY: {q(resend)}
  BTCPAY_API_KEY: {q(btcpay_key)}
  BTCPAY_STORE_ID: {q(btcpay_store)}
  BTCPAY_WEBHOOK_SECRET: {q(btcpay_wh)}
""")
print("Wrote base secrets")
PY

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

python3 - <<'PY' "$OUT_BASE" "$OUT_BTCPAY"
import sys
try:
    import yaml
except ImportError:
    for path, keys in [
        (sys.argv[1], ["DB_PASSWORD", "JWT_ED25519_PRIVATE_KEY", "JWT_ED25519_PUBLIC_KEYS", "JWT_ACTIVE_KEY_ID", "AGENT_AUTH_TOKEN", "REDIS_PASSWORD"]),
        (sys.argv[2], ["BTCPAY_POSTGRES_PASSWORD", "BTC_RPC_PASSWORD", "BTC_RPC_USER"]),
    ]:
        text = open(path).read()
        for k in keys:
            if f"{k}:" not in text:
                raise SystemExit(f"missing key {k} in {path}")
    print("secrets files written (basic key check OK)")
    raise SystemExit(0)

for path, keys in [
    (sys.argv[1], ["DB_PASSWORD", "JWT_ED25519_PRIVATE_KEY", "JWT_ED25519_PUBLIC_KEYS", "JWT_ACTIVE_KEY_ID", "AGENT_AUTH_TOKEN", "REDIS_PASSWORD", "NATS_USER", "NATS_PASSWORD"]),
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
