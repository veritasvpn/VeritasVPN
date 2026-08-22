#!/usr/bin/env bash
set -euo pipefail
umask 077

# Read-only restore rehearsal. This never writes to Kubernetes or production
# databases; it decrypts the newest archive into a temporary directory and
# checks that the expected SQL/configuration/key payloads are present.
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/veritasvpn}"
KEY_FILE="${KEY_FILE:-/root/.config/veritasvpn/backup.key}"
TEXTFILE_DIR="${TEXTFILE_DIR:-/var/lib/veritasvpn/metrics}"
latest="$(find "$BACKUP_ROOT" -maxdepth 1 -type f -name 'veritasvpn-*.tar.gz.enc' -printf '%T@ %p\n' | sort -nr | head -1 | cut -d' ' -f2-)"
test -n "$latest" && test -s "$KEY_FILE"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

hmac_key="$(cat "$KEY_FILE")"
expected="$(cat "$latest.hmac")"
actual="$(openssl dgst -sha256 -hmac "$hmac_key" -binary "$latest" | od -An -tx1 -v | tr -d ' \n')"
test "$expected" = "$actual"
openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:$KEY_FILE" -in "$latest" | tar -xzf - -C "$tmp"
for required in veritas.sql.gz btcpay.sql.gz veritas-k8s.yaml btcpay-k8s.yaml wireguard-private.key wireguard-state.txt; do
  test -s "$tmp/$required"
done
gzip -t "$tmp/veritas.sql.gz"
gzip -t "$tmp/btcpay.sql.gz"
install -d -m 755 "$TEXTFILE_DIR"
metrics_tmp="$(mktemp "$TEXTFILE_DIR/veritas_backup_restore.prom.XXXXXX")"
cat > "$metrics_tmp" <<EOF
# HELP veritas_backup_restore_rehearsal_last_success_timestamp Unix timestamp of the last isolated backup restore rehearsal.
# TYPE veritas_backup_restore_rehearsal_last_success_timestamp gauge
veritas_backup_restore_rehearsal_last_success_timestamp $(date +%s)
EOF
chmod 0644 "$metrics_tmp"
mv "$metrics_tmp" "$TEXTFILE_DIR/veritas_backup_restore.prom"
printf 'restore rehearsal passed: %s\n' "$latest"
