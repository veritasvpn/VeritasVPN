#!/usr/bin/env bash
set -euo pipefail

BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/veritasvpn}"
KEY_FILE="${KEY_FILE:-/root/.config/veritasvpn/backup.key}"
MAX_AGE_HOURS="${MAX_AGE_HOURS:-30}"
LOCK_FILE="${LOCK_FILE:-/run/lock/veritasvpn-backup-verify.lock}"

mkdir -p "$(dirname "$LOCK_FILE")"
exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "backup verification already running; exiting"
  exit 0
fi

[[ -d "$BACKUP_ROOT" ]] || { echo "backup directory missing: $BACKUP_ROOT" >&2; exit 1; }
[[ -s "$KEY_FILE" ]] || { echo "backup key missing: $KEY_FILE" >&2; exit 1; }

latest="$(
  find "$BACKUP_ROOT" -maxdepth 1 -type f -name 'veritasvpn-*.tar.gz.enc' -printf '%T@ %p\n' 2>/dev/null |
    sort -nr |
    sed -n '1s/^[^ ]* //p'
)"
[[ -n "$latest" && -s "$latest" ]] || { echo "no encrypted backup found in $BACKUP_ROOT" >&2; exit 1; }

mtime="$(stat -c '%Y' -- "$latest")"
now="$(date +%s)"
age_hours=$(( (now - mtime) / 3600 ))
if (( age_hours > MAX_AGE_HOURS )); then
  echo "latest encrypted backup is stale: ${age_hours}h old (limit ${MAX_AGE_HOURS}h)" >&2
  exit 1
fi

sha_file="${latest}.sha256"
hmac_file="${latest}.hmac"
if [[ -s "$sha_file" ]]; then
  (cd "$(dirname "$latest")" && sha256sum -c -- "$(basename "$sha_file")")
else
  echo "backup checksum sidecar missing: $sha_file" >&2
  exit 1
fi

if [[ -s "$hmac_file" ]]; then
  expected="$(tr -d '[:space:]' < "$hmac_file")"
  backup_key="$(tr -d '\r\n' < "$KEY_FILE")"
  actual="$(openssl dgst -sha256 -hmac "$backup_key" -binary "$latest" | od -An -tx1 -v | tr -d '[:space:]')"
  [[ "$expected" == "$actual" ]] || { echo "backup authentication failed" >&2; exit 1; }
else
  echo "backup authentication sidecar missing: $hmac_file" >&2
  exit 1
fi

work="$(mktemp -d /tmp/veritasvpn-backup-verify.XXXXXX)"
trap 'rm -rf "$work"' EXIT
openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:$KEY_FILE" -in "$latest" -out "$work/archive.tar.gz"
tar -tzf "$work/archive.tar.gz" > "$work/contents"

for required in ./veritas.sql.gz ./btcpay.sql.gz ./veritas-k8s.yaml ./btcpay-k8s.yaml ./wireguard-private.key; do
  grep -Fxq "$required" "$work/contents" || { echo "backup missing $required" >&2; exit 1; }
done

tar -xOf "$work/archive.tar.gz" ./veritas.sql.gz | gzip -t
tar -xOf "$work/archive.tar.gz" ./btcpay.sql.gz | gzip -t
[[ "$(tar -xOf "$work/archive.tar.gz" ./wireguard-private.key | wc -c)" -gt 40 ]] || {
  echo "backup contains an invalid WireGuard private key" >&2
  exit 1
}

echo "backup verified: $latest (${age_hours}h old)"
