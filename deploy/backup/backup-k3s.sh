#!/usr/bin/env bash
set -euo pipefail

BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/veritasvpn}"
KEY_FILE="${KEY_FILE:-/root/.config/veritasvpn/backup.key}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
WORK="$(mktemp -d)"
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

tar -C "$WORK" -czf - . | openssl enc -aes-256-cbc -pbkdf2 -salt -pass "file:$KEY_FILE" -out "$BACKUP_ROOT/veritasvpn-$STAMP.tar.gz.enc"
find "$BACKUP_ROOT" -maxdepth 1 -type f -name 'veritasvpn-*.tar.gz.enc' -mtime "+$RETENTION_DAYS" -delete
test -s "$BACKUP_ROOT/veritasvpn-$STAMP.tar.gz.enc"
sha256sum "$BACKUP_ROOT/veritasvpn-$STAMP.tar.gz.enc" > "$BACKUP_ROOT/veritasvpn-$STAMP.sha256"
