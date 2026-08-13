#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups/postgres}"
RETENTION_DAILY="${RETENTION_DAILY:-7}"
RETENTION_WEEKLY="${RETENTION_WEEKLY:-4}"
RETENTION_MONTHLY="${RETENTION_MONTHLY:-3}"
GPG_RECIPIENT="${GPG_RECIPIENT:-}"

mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/monthly"

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
DAY_OF_WEEK="$(date +%u)"
DAY_OF_MONTH="$(date +%d)"
DUMP_FILE="$BACKUP_DIR/daily/veritas-$TIMESTAMP.sql.gz"

echo "[pgbackup] dumping database"
docker compose exec -T postgres pg_dumpall -U veritas | gzip > "$DUMP_FILE"

if [ -n "$GPG_RECIPIENT" ]; then
  echo "[pgbackup] encrypting for $GPG_RECIPIENT"
  gpg --encrypt --recipient "$GPG_RECIPIENT" --output "$DUMP_FILE.gpg" "$DUMP_FILE"
  rm "$DUMP_FILE"
fi

echo "[pgbackup] dump size: $(du -h "$DUMP_FILE" 2>/dev/null || du -h "$DUMP_FILE.gpg")"

if [ "$DAY_OF_WEEK" -eq 7 ]; then
  cp "$DUMP_FILE" "$BACKUP_DIR/weekly/$(date +%Y-%m-%d)-weekly.sql.gz" 2>/dev/null || \
    cp "$DUMP_FILE.gpg" "$BACKUP_DIR/weekly/$(date +%Y-%m-%d)-weekly.sql.gz.gpg"
fi

if [ "$DAY_OF_MONTH" -eq 1 ]; then
  cp "$DUMP_FILE" "$BACKUP_DIR/monthly/$(date +%Y-%m)-monthly.sql.gz" 2>/dev/null || \
    cp "$DUMP_FILE.gpg" "$BACKUP_DIR/monthly/$(date +%Y-%m)-monthly.sql.gz.gpg"
fi

echo "[pgbackup] removing old backups"
find "$BACKUP_DIR/daily" -type f -mtime +"$RETENTION_DAILY" -delete
find "$BACKUP_DIR/weekly" -type f -mtime +$((RETENTION_WEEKLY * 7)) -delete
find "$BACKUP_DIR/monthly" -type f -mtime +$((RETENTION_MONTHLY * 31)) -delete

echo "[pgbackup] done"
