#!/usr/bin/env bash
set -euo pipefail
umask 077

BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/veritasvpn}"
KEY_FILE="${KEY_FILE:-/root/.config/veritasvpn/backup.key}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
R2_UPLOAD_REQUIRED="${R2_UPLOAD_REQUIRED:-false}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
WORK="$(mktemp -d)"
TEXTFILE_DIR="${TEXTFILE_DIR:-/var/lib/veritasvpn/metrics}"
trap 'rm -rf "$WORK"' EXIT

install -d -m 700 "$BACKUP_ROOT" "$(dirname "$KEY_FILE")"
if [ ! -s "$KEY_FILE" ]; then
  umask 077
  openssl rand -hex 32 > "$KEY_FILE"
fi

kubectl -n veritas exec postgres-0 -- pg_dumpall -U veritas | gzip -9 > "$WORK/veritas.sql.gz"
kubectl -n btcpay exec postgres-btcpay-0 -- pg_dumpall -U btcpay | gzip -9 > "$WORK/btcpay.sql.gz"
kubectl -n veritas get configmap,secret -o yaml > "$WORK/veritas-k8s.yaml"
kubectl -n btcpay get configmap,secret -o yaml > "$WORK/btcpay-k8s.yaml"
install -m 600 /home/jpg/VeritasVPN/data/wireguard/private.key "$WORK/wireguard-private.key"
wg show all dump > "$WORK/wireguard-state.txt"
# The Android signing identity is an irreplaceable release credential. Include
# it only inside this already encrypted and authenticated off-site backup.
if [[ -s /etc/veritasvpn/android-signing/veritasvpn-release.p12 && -s /etc/veritasvpn/android-signing/signing.env ]]; then
  install -d -m 700 "$WORK/android-signing"
  install -m 600 /etc/veritasvpn/android-signing/veritasvpn-release.p12 "$WORK/android-signing/veritasvpn-release.p12"
  install -m 600 /etc/veritasvpn/android-signing/signing.env "$WORK/android-signing/signing.env"
fi

archive="$BACKUP_ROOT/veritasvpn-$STAMP.tar.gz.enc"
tar -C "$WORK" -czf - . | openssl enc -aes-256-cbc -pbkdf2 -salt -pass "file:$KEY_FILE" -out "$archive"
# CBC encryption is authenticated with an encrypt-then-MAC sidecar. This keeps
# existing restore tooling compatible while detecting tampering before decrypt.
hmac_key="$(cat "$KEY_FILE")"
openssl dgst -sha256 -hmac "$hmac_key" -binary "$archive" | od -An -tx1 -v | tr -d ' \n' > "$archive.hmac"
find "$BACKUP_ROOT" -maxdepth 1 -type f -name 'veritasvpn-*.tar.gz.enc*' -mtime "+$RETENTION_DAYS" -delete
test -s "$archive" && test -s "$archive.hmac"
sha256sum "$archive" > "$archive.sha256"

# Verify the authenticated archive before reporting success. This is a
# restore rehearsal: it decrypts and enumerates the tar stream without
# touching Kubernetes or overwriting live data.
expected_hmac="$(cat "$archive.hmac")"
actual_hmac="$(openssl dgst -sha256 -hmac "$hmac_key" -binary "$archive" | od -An -tx1 -v | tr -d ' \n')"
test "$expected_hmac" = "$actual_hmac"
openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:$KEY_FILE" -in "$archive" | tar -tzf - >/dev/null

offsite_success=0
if [[ -n "${R2_ENDPOINT:-}" && -n "${R2_BUCKET:-}" && -n "${R2_ACCESS_KEY_ID:-}" && -n "${R2_SECRET_ACCESS_KEY:-}" ]]; then
  python3 /home/jpg/VeritasVPN/deploy/backup/r2-upload.py \
    --prefix "${R2_PREFIX:-veritasvpn/backups}/$STAMP" \
    --file "$archive" --file "$archive.hmac" --file "$archive.sha256"
  offsite_success=1
elif [[ "${R2_UPLOAD_REQUIRED,,}" == "true" ]]; then
  echo "[backup] ERROR: R2 credentials are missing; off-site upload is required" >&2
  exit 1
else
  echo "[backup] warning: R2 credentials are not configured; local backup only" >&2
fi

install -d -m 755 "$TEXTFILE_DIR"
tmp_metrics="$(mktemp "$TEXTFILE_DIR/veritas_backup.prom.XXXXXX")"
cat > "$tmp_metrics" <<EOF
# HELP veritas_backup_last_success_timestamp Unix timestamp of the last encrypted backup that passed restore validation.
# TYPE veritas_backup_last_success_timestamp gauge
veritas_backup_last_success_timestamp $(date +%s)
# HELP veritas_backup_archive_bytes Size of the last validated encrypted backup in bytes.
# TYPE veritas_backup_archive_bytes gauge
veritas_backup_archive_bytes $(stat -c %s "$archive")
# HELP veritas_backup_offsite_last_success_timestamp Unix timestamp of the last successful R2 upload.
# TYPE veritas_backup_offsite_last_success_timestamp gauge
veritas_backup_offsite_last_success_timestamp $([[ "$offsite_success" -eq 1 ]] && date +%s || echo 0)
EOF
chown root:root "$tmp_metrics" 2>/dev/null || true
chmod 0644 "$tmp_metrics"
mv "$tmp_metrics" "$TEXTFILE_DIR/veritas_backup.prom"
