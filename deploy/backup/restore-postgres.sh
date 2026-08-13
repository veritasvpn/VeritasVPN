#!/usr/bin/env bash
set -euo pipefail

BACKUP_FILE="${1:-}"

if [ -z "$BACKUP_FILE" ]; then
  echo "usage: $0 <backup-file.sql.gz|.gpg>"
  echo "available backups:"
  find ./backups/postgres -type f 2>/dev/null | sort -r | head -20 || echo "  none found"
  exit 1
fi

if [ ! -f "$BACKUP_FILE" ]; then
  echo "error: $BACKUP_FILE not found"
  exit 1
fi

echo "[pgrestore] this will OVERWRITE the current database"
echo "[pgrestore] backup: $BACKUP_FILE"
read -rp "type 'yes' to confirm: " CONFIRM
if [ "$CONFIRM" != "yes" ]; then
  echo "aborted"
  exit 0
fi

echo "[pgrestore] dropping existing connections"
docker compose exec -T postgres psql -U veritas -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='veritas' AND pid <> pg_backend_pid();" 2>/dev/null || true

if [[ "$BACKUP_FILE" == *.gpg ]]; then
  gpg --decrypt "$BACKUP_FILE" | gunzip | docker compose exec -T postgres psql -U veritas
else
  gunzip -c "$BACKUP_FILE" | docker compose exec -T postgres psql -U veritas
fi

echo "[pgrestore] restore complete"
