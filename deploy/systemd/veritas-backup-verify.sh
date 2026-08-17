#!/usr/bin/env bash
set -euo pipefail

BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/veritasvpn}"
KEY_FILE="${KEY_FILE:-/root/.config/veritasvpn/backup.key}"
latest="$(find "$BACKUP_ROOT" -maxdepth 1 -type f -name 'veritasvpn-*.tar.gz.enc' -printf '%T@ %p\n' | sort -nr | head -1 | cut -d' ' -f2-)"
[[ -n "$latest" && -s "$latest" ]] || { echo 'no encrypted backup found' >&2; exit 1; }
[[ -s "$KEY_FILE" ]] || { echo 'backup key missing' >&2; exit 1; }

if [[ -s "$latest.sha256" ]]; then
  (cd "$(dirname "$latest")" && sha256sum -c "$(basename "$latest.sha256")")
fi
if [[ -s "$latest.hmac" ]]; then
  expected="$(<"$latest.hmac")"
  backup_key="$(<"$KEY_FILE")"
  actual="$(openssl dgst -sha256 -hmac "$backup_key" -binary "$latest" | od -An -tx1 -v | tr -d '[:space:]')"
  [[ "$expected" == "$actual" ]] || { echo 'backup authentication failed' >&2; exit 1; }
else
  echo 'warning: legacy backup has no authentication sidecar' >&2
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:$KEY_FILE" -in "$latest" -out "$work/archive.tar.gz"
tar -tzf "$work/archive.tar.gz" > "$work/contents"
for required in ./veritas.sql.gz ./btcpay.sql.gz ./veritas-k8s.yaml ./btcpay-k8s.yaml ./wireguard-private.key; do
  grep -Fxq "$required" "$work/contents" || { echo "backup missing $required" >&2; exit 1; }
done
gzip -t <(tar -xOf "$work/archive.tar.gz" ./veritas.sql.gz)
gzip -t <(tar -xOf "$work/archive.tar.gz" ./btcpay.sql.gz)
test "$(tar -xOf "$work/archive.tar.gz" ./wireguard-private.key | wc -c)" -gt 40
echo "backup verified: $latest"
