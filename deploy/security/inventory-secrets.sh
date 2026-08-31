#!/usr/bin/env bash
set -euo pipefail

echo "=== credential inventory (no values printed) ==="
echo ""

check_var() {
  local var="$1"
  local desc="$2"
  if [ -n "${!var:-}" ]; then
    echo "  [SET] $var — $desc"
  else
    echo "  [MISSING] $var — $desc"
  fi
}

check_file() {
  local path="$1"
  local desc="$2"
  if [ -f "$path" ]; then
    echo "  [EXISTS] $path — $desc ($(stat -c '%a %U:%G' "$path"))"
  else
    echo "  [MISSING] $path — $desc"
  fi
}

echo "--- environment variables ---"
check_var "JWT_ED25519_PRIVATE_KEY" "Ed25519 JWT private key (auth mint)"
check_var "JWT_ED25519_PUBLIC_KEYS" "Ed25519 JWT public key JSON map"
check_var "JWT_ACTIVE_KEY_ID" "Active JWT kid"
check_var "AGENT_AUTH_TOKEN" "Agent auth token"
check_var "DB_PASSWORD" "PostgreSQL password"
check_var "REDIS_PASSWORD" "Redis password"
check_var "CLOUDFLARE_TUNNEL_TOKEN" "Cloudflare tunnel token"
check_var "BTCPAY_API_KEY" "BTCPay API key"
check_var "BTCPAY_WEBHOOK_SECRET" "BTCPay webhook secret"

echo ""
echo "--- files ---"
check_file ".env" "Production env file"
check_file "data/wireguard/private.key" "WireGuard server private key"
check_file "data/wireguard/wg0.conf" "WireGuard config"
check_file ".cloudflared-credentials.json" "Cloudflare tunnel credentials"

echo ""
echo "--- git safety ---"
if git check-ignore .env &>/dev/null; then
  echo "  [OK] .env is gitignored"
else
  echo "  [WARNING] .env is NOT gitignored"
fi

echo ""
echo "--- default credentials in compose files ---"
grep -n "change-me\|changeme\|TODO\|FIXME" docker-compose.yml docker-compose.prod.yml 2>/dev/null || echo "  none found"

echo ""
echo "[done] review the [MISSING] and [WARNING] items above"
