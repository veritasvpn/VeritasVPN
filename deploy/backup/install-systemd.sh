#!/usr/bin/env bash
set -euo pipefail
umask 022

if [[ "$(id -u)" -ne 0 ]]; then
  echo "run as root" >&2
  exit 2
fi
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
LIBEXEC=/usr/local/libexec/veritasvpn-backup
KEY_DIR=/root/.config/veritasvpn
KEY_FILE="$KEY_DIR/backup.key"
install -d -o root -g root -m 0755 "$LIBEXEC"
install -d -o root -g root -m 0700 "$KEY_DIR" /var/backups/veritasvpn
install -d -o root -g root -m 0755 /var/lib/veritasvpn/metrics
if [[ ! -e "$KEY_FILE" ]]; then
  openssl rand -hex 32 > "$KEY_FILE"
fi
[[ -f "$KEY_FILE" && ! -L "$KEY_FILE" ]] || { echo "invalid backup key path" >&2; exit 1; }
chown root:root "$KEY_FILE"
chmod 0600 "$KEY_FILE"

# Lock down migration-era snapshots that earlier scripts created with a broad
# umask inside the checkout.
if [[ -d "$ROOT/backups" && ! -L "$ROOT/backups" ]]; then
  find "$ROOT/backups" -type d -exec chmod 0700 {} +
  find "$ROOT/backups" -type f -exec chmod 0600 {} +
fi
install -o root -g root -m 0755 "$ROOT/deploy/backup/backup-k3s.sh" "$LIBEXEC/backup-k3s.sh"
install -o root -g root -m 0755 "$ROOT/deploy/backup/restore-test.sh" "$LIBEXEC/restore-test.sh"
install -o root -g root -m 0755 "$ROOT/deploy/systemd/veritas-backup-verify.sh" "$LIBEXEC/verify-backup.sh"
install -o root -g root -m 0755 "$ROOT/deploy/backup/r2-upload.py" "$LIBEXEC/r2-upload.py"
for unit in veritas-backup.service veritas-backup.timer veritas-backup-verify.service veritas-backup-verify.timer veritas-backup-restore-rehearsal.service veritas-backup-restore-rehearsal.timer; do
  install -o root -g root -m 0644 "$ROOT/deploy/systemd/$unit" "/etc/systemd/system/$unit"
done
systemctl daemon-reload
systemctl enable --now veritas-backup.timer veritas-backup-verify.timer veritas-backup-restore-rehearsal.timer
printf 'Installed immutable backup programs in %s
' "$LIBEXEC"
