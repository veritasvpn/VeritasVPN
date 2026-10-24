#!/usr/bin/env bash
# A backup archive must not contain a WireGuard peer endpoint (the client's
# real public IP). Restore still has to find the database dumps, Kubernetes
# objects, and the server private key.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$ROOT"
LIB="$ROOT/deploy/backup/strip-peer-endpoints.sh"
CLIENT_IP="203.0.113.77"
SERVER_IP="198.51.100.10"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

peers_sql="$(awk '/CREATE TABLE IF NOT EXISTS peers \(/,/^\);/' services/wg-manager/migrations/001_initial.sql)"
if printf '%s\n' "$peers_sql" | grep -Eiq 'endpoint|client_ip|remote_addr|public_ip'; then
  fail "peers table has a column that would store a client address in pg_dump"
fi
if grep -RInE 'ALTER TABLE peers' services/wg-manager --include='*.sql' | grep -Eiq 'endpoint|client_ip|remote_addr|public_ip'; then
  fail "a peers migration adds a client address column"
fi

# shellcheck source=strip-peer-endpoints.sh
source "$LIB"

showconf="$(mktemp)"
bin="$(mktemp -d)"
cleanup() { rm -rf "$bin" "$showconf"; }
trap cleanup EXIT

cat > "$bin/kubectl" <<'EOF'
#!/bin/bash
set -euo pipefail
joined="$*"
if [[ "$joined" == *"pg_dumpall"* && "$joined" == *"btcpay"* ]]; then
  printf '%s\n' '-- btcpay fixture'
  exit 0
fi
if [[ "$joined" == *"pg_dumpall"* ]]; then
  printf '%s\n' '-- veritas fixture'
  exit 0
fi
if [[ "$joined" == *"configmap,secret"* ]]; then
  cat <<'YAML'
apiVersion: v1
kind: ConfigMap
data:
  MANAGER_ENDPOINT: http://wg-manager:8080
  PUBLIC_IP: 198.51.100.10
  LAN_ENDPOINT_PORT: "51820"
  STEALTH_ENDPOINT_HOST: ""
YAML
  exit 0
fi
printf 'unexpected kubectl: %s\n' "$joined" >&2
exit 1
EOF
cat > "$bin/wg" <<'EOF'
#!/bin/bash
set -euo pipefail
if [[ "${1:-}" == "show" && "${2:-}" == "all" && "${3:-}" == "dump" ]]; then
  printf '%s\n' 'SERVERPRIVKEY SERVERPUB 51820 off'
  printf '%s\n' 'PEERPUB PEERPSK 203.0.113.77:4242 10.7.0.2/32 1710000000 100 200 25'
  printf '%s\n' 'PEERPUB6 PEERPSK [2001:db8::77]:4242 10.7.0.3/32 1710000000 0 0 25'
  exit 0
fi
if [[ "${1:-}" == "showconf" ]]; then
  cat <<'CFG'
[Interface]
PrivateKey = SERVERPRIVKEY
ListenPort = 51820

[Peer]
PublicKey = PEERPUB
PresharedKey = PEERPSK
Endpoint = 203.0.113.77:4242
AllowedIPs = 10.7.0.2/32
CFG
  exit 0
fi
if [[ "${1:-}" == "show" ]]; then
  cat <<'CFG'
interface: wg0
  public key: SERVERPUB
  private key: (hidden)
  listening port: 51820

peer: PEERPUB
  endpoint: 203.0.113.77:4242
  allowed ips: 10.7.0.2/32
  latest handshake: 2 seconds ago
CFG
  exit 0
fi
printf 'unexpected wg: %s\n' "$*" >&2
exit 1
EOF
chmod 0755 "$bin/kubectl" "$bin/wg"
export PATH="$bin:$PATH"

capture_wg_showconf wg0 "$showconf"
grep -q 'PrivateKey = SERVERPRIVKEY' "$showconf" || fail "showconf dropped the server private key"
grep -q 'AllowedIPs = 10.7.0.2/32' "$showconf" || fail "showconf dropped allowed IPs"
if grep -q "$CLIENT_IP" "$showconf"; then
  fail "showconf still contains the peer endpoint"
fi

dump_out="$(printf '%s\n' \
  'SERVERPRIVKEY SERVERPUB 51820 off' \
  'PEERPUB PEERPSK 203.0.113.77:4242 10.7.0.2/32 1710000000 100 200 25' \
  'PEERPUB6 PEERPSK [2001:db8::77]:4242 10.7.0.3/32 1710000000 0 0 25' \
  | bash "$LIB")"
printf '%s\n' "$dump_out" | grep -q 'SERVERPRIVKEY SERVERPUB 51820 off' || fail "dump sanitizer rewrote the interface private key"
printf '%s\n' "$dump_out" | grep -q 'PEERPUB PEERPSK (none) 10.7.0.2/32' || fail "dump sanitizer dropped the peer"
if printf '%s\n' "$dump_out" | grep -q "$CLIENT_IP"; then
  fail "dump sanitizer left an IPv4 endpoint"
fi
if printf '%s\n' "$dump_out" | grep -q '2001:db8::77'; then
  fail "dump sanitizer left an IPv6 endpoint"
fi

human_out="$(wg show wg0 | bash "$LIB")"
printf '%s\n' "$human_out" | grep -q 'latest handshake: 2 seconds ago' || fail "human output lost non-address peer state"
if printf '%s\n' "$human_out" | grep -q "$CLIENT_IP"; then
  fail "human wg show still contains the peer endpoint"
fi

tree="$(mktemp -d)"
printf 'Endpoint = %s:4242\nPrivateKey = keep-me\n' "$CLIENT_IP" > "$tree/wg0.conf"
printf 'do-not-touch\n' > "$tree/private.key"
sanitize_wireguard_tree "$tree"
grep -q 'PrivateKey = keep-me' "$tree/wg0.conf" || fail "copied config lost its private key"
grep -q 'do-not-touch' "$tree/private.key" || fail "private key file was rewritten"
if grep -q "$CLIENT_IP" "$tree/wg0.conf"; then
  fail "copied WireGuard config still contains the peer endpoint"
fi
rm -rf "$tree"

bad="$(mktemp -d)"
printf 'Endpoint = %s:4242\n' "$CLIENT_IP" > "$bad/wg0.conf"
check_err="$(bash "$LIB" --check "$bad" 2>&1 || true)"
if [[ "$check_err" != *"refusing to archive"* ]]; then
  fail "endpoint check did not refuse a peer endpoint"
fi
if printf '%s\n' "$check_err" | grep -q "$CLIENT_IP"; then
  fail "endpoint check wrote the client IP to its error"
fi
rm -rf "$bad"

good="$(mktemp -d)"
cat > "$good/veritas-k8s.yaml" <<EOF
MANAGER_ENDPOINT: http://wg-manager:8080
PUBLIC_IP: ${SERVER_IP}
LAN_ENDPOINT_PORT: "51820"
EOF
bash "$LIB" --check "$good"
rm -rf "$good"

if grep -nE '^[[:space:]]*wg[[:space:]]+show' deploy/backup/backup-k3s.sh; then
  fail "cluster backup still invokes wg show"
fi

workroot="$(mktemp -d)"
keyfile="$workroot/backup.key"
priv="$workroot/server.key"
printf 'test-backup-key\n' > "$keyfile"
printf 'test-server-private-key-material\n' > "$priv"
chmod 0600 "$keyfile" "$priv"
backup_dir="$workroot/backups"
metrics="$workroot/metrics"
mkdir -p "$backup_dir" "$metrics"

BACKUP_ROOT="$backup_dir" \
  KEY_FILE="$keyfile" \
  WG_PRIVATE_KEY_FILE="$priv" \
  TEXTFILE_DIR="$metrics" \
  R2_UPLOAD_REQUIRED=false \
  bash "$ROOT/deploy/backup/backup-k3s.sh" >/dev/null

archive="$(find "$backup_dir" -maxdepth 1 -type f -name 'veritasvpn-*.tar.gz.enc' -printf '%T@ %p\n' | sort -nr | head -1 | cut -d' ' -f2-)"
[[ -n "$archive" && -s "$archive" ]] || fail "backup did not write an archive"
[[ -s "$archive.hmac" && -s "$archive.sha256" ]] || fail "backup did not write authentication sidecars"

extract="$workroot/extract"
mkdir -p "$extract"
openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:$keyfile" -in "$archive" | tar -xzf - -C "$extract"
for required in veritas.sql.gz btcpay.sql.gz veritas-k8s.yaml btcpay-k8s.yaml wireguard-private.key; do
  [[ -s "$extract/$required" ]] || fail "restore payload missing: $required"
done
if [[ -e "$extract/wireguard-state.txt" ]]; then
  fail "backup still archives wireguard-state.txt"
fi
if grep -a -R -F -q "$CLIENT_IP" "$extract"; then
  fail "backup archive contains the peer endpoint"
fi
if grep -a -R -F -q '2001:db8::77' "$extract"; then
  fail "backup archive contains the IPv6 peer endpoint"
fi
grep -q "$SERVER_IP" "$extract/veritas-k8s.yaml" || fail "backup dropped the server public IP"
cmp -s "$priv" "$extract/wireguard-private.key" || fail "restore lost the server private key"
gzip -t "$extract/veritas.sql.gz"
gzip -t "$extract/btcpay.sql.gz"

BACKUP_ROOT="$backup_dir" KEY_FILE="$keyfile" bash "$ROOT/deploy/backup/verify-backup.sh" >/dev/null
BACKUP_ROOT="$backup_dir" KEY_FILE="$keyfile" TEXTFILE_DIR="$metrics" bash "$ROOT/deploy/backup/restore-test.sh" >/dev/null
[[ -s "$metrics/veritas_backup_restore.prom" ]] || fail "restore rehearsal did not record success"

printf 'backup omits peer endpoints and restore rehearsal passed\n'
