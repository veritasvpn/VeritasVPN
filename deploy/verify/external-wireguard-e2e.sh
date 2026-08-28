#!/usr/bin/env bash
set -euo pipefail

API_BASE="${API_BASE:-https://api.veritasvpn.cloud}"
ACCOUNT_ID="${VERITAS_E2E_ACCOUNT_ID:-}"
INTERFACE="veritas-e2e"
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
printf '[1/8] authenticating synthetic account\n'
signin_code="$(curl --silent --show-error --max-time 20 \
  -o "$WORKDIR/signin.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg account_id "$ACCOUNT_ID" '{account_id:$account_id}')" \
  "$API_BASE/api/v1/auth/signin-account")"
if [[ "$signin_code" != "200" ]]; then
  printf 'sign-in failed with HTTP %s: %s\n' "$signin_code" "$(jq -r '.error // "unknown error"' "$WORKDIR/signin.json")" >&2
  exit 1
fi
ACCESS_TOKEN="$(jq -r '.access_token // empty' "$WORKDIR/signin.json")"
[[ -n "$ACCESS_TOKEN" ]] || { printf 'sign-in response did not contain an access token\n' >&2; exit 1; }

printf '[2/8] provisioning a disposable WireGuard peer\n'
wg genkey >"$WORKDIR/client.key"
wg pubkey <"$WORKDIR/client.key" >"$WORKDIR/client.pub"
peer_code="$(curl --silent --show-error --max-time 30 \
  -o "$WORKDIR/peer.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --data "$(jq -nc --arg public_key "$(<"$WORKDIR/client.pub")" '{public_key:$public_key}')" \
  "$API_BASE/api/v1/wg/peers")"
if [[ "$peer_code" != "200" ]]; then
  printf 'peer provisioning failed with HTTP %s: %s\n' "$peer_code" "$(jq -r '.error // "unknown error"' "$WORKDIR/peer.json")" >&2
  exit 1
fi

PEER_ID="$(jq -r '.peer_id // empty' "$WORKDIR/peer.json")"
SERVER_KEY="$(jq -r '.server_public_key // empty' "$WORKDIR/peer.json")"
PRESHARED_KEY="$(jq -r '.preshared_key // empty' "$WORKDIR/peer.json")"
ENDPOINT="$(jq -r '.server_endpoint // empty' "$WORKDIR/peer.json")"
ADDRESS="$(jq -r '.assigned_ip // .address // empty' "$WORKDIR/peer.json")"
DNS_SERVER="$(jq -r '.dns_server // empty' "$WORKDIR/peer.json")"
for value in PEER_ID SERVER_KEY PRESHARED_KEY ENDPOINT ADDRESS DNS_SERVER; do
  [[ -n "${!value}" ]] || { printf 'peer response is missing %s\n' "$value" >&2; exit 1; }
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

printf '[3/8] bringing up the tunnel\n'
wg-quick up "$CONFIG" >/dev/null
TUNNEL_UP=1

printf '[4/8] validating a cryptographic handshake\n'
dig +time=3 +tries=1 @"$DNS_SERVER" api.veritasvpn.cloud A >/dev/null 2>&1 || true
handshake=0
for _ in $(seq 1 20); do
  handshake="$(wg show "$INTERFACE" latest-handshakes | awk '{print $2; exit}')"
  if [[ "$handshake" =~ ^[0-9]+$ ]] && (( handshake > 0 )); then break; fi
  sleep 1
done
if [[ ! "$handshake" =~ ^[0-9]+$ ]] || (( handshake == 0 )); then
  printf 'WireGuard handshake was not established\n' >&2
  exit 1
fi

printf '[5/8] validating VPN DNS\n'
dig +short +time=5 +tries=2 @"$DNS_SERVER" example.com A \
  | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'

printf '[6/8] validating HTTPS internet egress\n'
EGRESS_IP="$(curl --fail --silent --show-error --max-time 15 https://api.ipify.org)"
ENDPOINT_HOST="${ENDPOINT%:*}"
if [[ "$ENDPOINT_HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  EXPECTED_IP="$ENDPOINT_HOST"
else
  EXPECTED_IP="$(getent ahostsv4 "$ENDPOINT_HOST" | awk '{print $1; exit}')"
fi
if [[ -z "$EGRESS_IP" || -z "$EXPECTED_IP" || "$EGRESS_IP" != "$EXPECTED_IP" ]]; then
  printf 'egress mismatch: expected VPN endpoint, received %s\n' "${EGRESS_IP:-none}" >&2
  exit 1
fi
curl --fail --silent --show-error --max-time 15 https://www.cloudflare.com/cdn-cgi/trace >/dev/null

printf '[7/8] disconnecting and revoking the peer\n'
wg-quick down "$CONFIG" >/dev/null
TUNNEL_UP=0
delete_code="$(curl --silent --show-error --max-time 20 \
  -o "$WORKDIR/delete.out" -w '%{http_code}' -X DELETE \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "$API_BASE/api/v1/wg/peers/$PEER_ID")"
if [[ "$delete_code" != "204" ]]; then
  printf 'peer revocation failed with HTTP %s\n' "$delete_code" >&2
  exit 1
fi
PEER_ID=""

printf '[8/8] validating normal internet restoration\n'
curl --fail --silent --show-error --max-time 15 "$API_BASE/healthz" >/dev/null
printf 'External WireGuard end-to-end test: PASS\n'
