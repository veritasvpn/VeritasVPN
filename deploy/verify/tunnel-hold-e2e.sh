#!/usr/bin/env bash
# Long-hold WireGuard tunnel: 6 × 60s with handshake freshness and egress checks.
# Intended for Dell cron (root). Reuses the same synthetic account as short E2E.
set -euo pipefail

API_BASE="${API_BASE:-https://api.veritasvpn.cloud}"
ACCOUNT_ID="${VERITAS_E2E_ACCOUNT_ID:-}"
HOLD_SECONDS="${HOLD_SECONDS:-360}"
CHECK_INTERVAL="${CHECK_INTERVAL:-60}"
MAX_HANDSHAKE_AGE="${MAX_HANDSHAKE_AGE:-150}"
# Require this many consecutive stale samples before failing (survives one check blip).
STALE_FAIL_STREAK="${STALE_FAIL_STREAK:-2}"
INTERFACE="veritas-hold"
WORKDIR="$(mktemp -d)"
CONFIG="$WORKDIR/$INTERFACE.conf"
ACCESS_TOKEN=""
PEER_ID=""
TUNNEL_UP=0

cleanup() {
  status=$?
  trap - EXIT INT TERM
  set +e
  if (( TUNNEL_UP == 1 )); then
    wg-quick down "$CONFIG" >/dev/null 2>&1
  fi
  if [[ -n "$ACCESS_TOKEN" && -n "$PEER_ID" ]]; then
    curl --silent --show-error --max-time 15 -X DELETE \
      -H "Authorization: Bearer $ACCESS_TOKEN" \
      "$API_BASE/api/v1/wg/peers/$PEER_ID" >/dev/null
  fi
  if [[ -n "$ACCESS_TOKEN" ]]; then
    curl --silent --show-error --max-time 15 -X POST \
      -H "Authorization: Bearer $ACCESS_TOKEN" \
      "$API_BASE/api/v1/auth/logout-all" >/dev/null
  fi
  find "$WORKDIR" -type f -exec shred -u '{}' + 2>/dev/null || true
  rmdir "$WORKDIR" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT INT TERM

if [[ "$(id -u)" -ne 0 ]]; then
  printf 'run this test as root so wg-quick can create the interface\n' >&2
  exit 2
fi
if [[ -z "$ACCOUNT_ID" ]]; then
  printf 'VERITAS_E2E_ACCOUNT_ID is required\n' >&2
  exit 2
fi
for command in curl jq wg wg-quick dig shred; do
  command -v "$command" >/dev/null || { printf 'missing command: %s\n' "$command" >&2; exit 2; }
done

umask 077
printf '[1/5] authenticating\n'
signin_code="$(curl --silent --show-error --max-time 20 \
  -o "$WORKDIR/signin.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg account_id "$ACCOUNT_ID" '{account_id:$account_id}')" \
  "$API_BASE/api/v1/auth/signin-account")"
[[ "$signin_code" == "200" ]] || { printf 'sign-in failed HTTP %s\n' "$signin_code" >&2; exit 1; }
ACCESS_TOKEN="$(jq -r '.access_token // empty' "$WORKDIR/signin.json")"
[[ -n "$ACCESS_TOKEN" ]] || exit 1

printf '[2/5] provisioning peer\n'
wg genkey >"$WORKDIR/client.key"
wg pubkey <"$WORKDIR/client.key" >"$WORKDIR/client.pub"
peer_code="$(curl --silent --show-error --max-time 30 \
  -o "$WORKDIR/peer.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --data "$(jq -nc --arg public_key "$(<"$WORKDIR/client.pub")" '{public_key:$public_key}')" \
  "$API_BASE/api/v1/wg/peers")"
[[ "$peer_code" == "200" ]] || { printf 'peer failed HTTP %s\n' "$peer_code" >&2; exit 1; }

PEER_ID="$(jq -r '.peer_id // empty' "$WORKDIR/peer.json")"
SERVER_KEY="$(jq -r '.server_public_key // empty' "$WORKDIR/peer.json")"
PRESHARED_KEY="$(jq -r '.preshared_key // empty' "$WORKDIR/peer.json")"
ENDPOINT="$(jq -r '.server_endpoint // empty' "$WORKDIR/peer.json")"
ADDRESS="$(jq -r '.assigned_ip // .address // empty' "$WORKDIR/peer.json")"
DNS_SERVER="$(jq -r '.dns_server // empty' "$WORKDIR/peer.json")"
for value in PEER_ID SERVER_KEY PRESHARED_KEY ENDPOINT ADDRESS DNS_SERVER; do
  [[ -n "${!value}" ]] || { printf 'peer response missing %s\n' "$value" >&2; exit 1; }
done

cat >"$CONFIG" <<EOF
[Interface]
PrivateKey = $(<"$WORKDIR/client.key")
Address = $ADDRESS

[Peer]
PublicKey = $SERVER_KEY
PresharedKey = $PRESHARED_KEY
Endpoint = $ENDPOINT
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
EOF

printf '[3/5] bringing up tunnel\n'
wg-quick up "$CONFIG" >/dev/null
TUNNEL_UP=1

dig +time=3 +tries=1 @"$DNS_SERVER" api.veritasvpn.cloud A >/dev/null 2>&1 || true
handshake=0
for _ in $(seq 1 20); do
  handshake="$(wg show "$INTERFACE" latest-handshakes | awk '{print $2; exit}')"
  if [[ "$handshake" =~ ^[0-9]+$ ]] && (( handshake > 0 )); then break; fi
  sleep 1
done
if [[ ! "$handshake" =~ ^[0-9]+$ ]] || (( handshake == 0 )); then
  printf 'handshake not established\n' >&2
  exit 1
fi

ENDPOINT_HOST="${ENDPOINT%:*}"
if [[ "$ENDPOINT_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  EXPECTED_IP="$ENDPOINT_HOST"
else
  EXPECTED_IP="$(getent ahostsv4 "$ENDPOINT_HOST" | awk '{print $1; exit}')"
fi

printf '[4/5] holding tunnel for %ss (interval %ss, max handshake age %ss, stale streak %s)\n' \
  "$HOLD_SECONDS" "$CHECK_INTERVAL" "$MAX_HANDSHAKE_AGE" "$STALE_FAIL_STREAK"
elapsed=0
stale_streak=0
while (( elapsed < HOLD_SECONDS )); do
  sleep "$CHECK_INTERVAL"
  elapsed=$((elapsed + CHECK_INTERVAL))
  if ! ip link show "$INTERFACE" >/dev/null 2>&1; then
    printf 'interface %s disappeared at t=%ss\n' "$INTERFACE" "$elapsed" >&2
    exit 1
  fi
  now="$(date +%s)"
  hs="$(wg show "$INTERFACE" latest-handshakes | awk '{print $2; exit}')"
  if [[ ! "$hs" =~ ^[0-9]+$ ]] || (( hs == 0 )); then
    printf 'handshake lost at t=%ss\n' "$elapsed" >&2
    exit 1
  fi
  age=$((now - hs))
  if (( age > MAX_HANDSHAKE_AGE )); then
    stale_streak=$((stale_streak + 1))
    printf '  t=%ss WARN stale handshake_age=%ss (streak %s/%s)\n' \
      "$elapsed" "$age" "$stale_streak" "$STALE_FAIL_STREAK"
    if (( stale_streak >= STALE_FAIL_STREAK )); then
      printf 'stale handshake age=%ss for %s checks at t=%ss (max %ss) — possible 2m flap\n' \
        "$age" "$stale_streak" "$elapsed" "$MAX_HANDSHAKE_AGE" >&2
      exit 1
    fi
  else
    stale_streak=0
  fi
  EGRESS_IP="$(curl --fail --silent --show-error --max-time 15 https://api.ipify.org)"
  if [[ -z "$EGRESS_IP" || -z "$EXPECTED_IP" || "$EGRESS_IP" != "$EXPECTED_IP" ]]; then
    printf 'egress mismatch at t=%ss: got %s expected %s\n' \
      "$elapsed" "${EGRESS_IP:-none}" "${EXPECTED_IP:-none}" >&2
    exit 1
  fi
  if (( stale_streak == 0 )); then
    printf '  t=%ss ok handshake_age=%ss egress=%s\n' "$elapsed" "$age" "$EGRESS_IP"
  fi
done

printf '[5/5] teardown\n'
wg-quick down "$CONFIG" >/dev/null
TUNNEL_UP=0
curl --silent --show-error --max-time 20 -X DELETE \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "$API_BASE/api/v1/wg/peers/$PEER_ID" >/dev/null
PEER_ID=""
printf 'Tunnel hold e2e: PASS (%ss)\n' "$HOLD_SECONDS"
