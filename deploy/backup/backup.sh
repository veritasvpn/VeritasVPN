#!/usr/bin/env bash
set -euo pipefail

BACKUP_ROOT="${1:-./backups}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$BACKUP_ROOT/$TIMESTAMP"
mkdir -p "$BACKUP_DIR"

echo "[backup] creating backup in $BACKUP_DIR"

if [ -f .env ]; then
  install -m 600 .env "$BACKUP_DIR/.env"
  echo "  .env backed up (600)"
fi

if [ -d data/wireguard ]; then
  cp -rp data/wireguard "$BACKUP_DIR/wireguard"
  chmod -R 700 "$BACKUP_DIR/wireguard"
  echo "  WireGuard state backed up"
fi

docker compose exec -T postgres pg_dumpall -U veritas 2>/dev/null > "$BACKUP_DIR/pg_dumpall.sql" || {
  echo "  WARNING: pg_dumpall failed — is postgres running?"
}
if [ -s "$BACKUP_DIR/pg_dumpall.sql" ]; then
  gzip "$BACKUP_DIR/pg_dumpall.sql"
  echo "  PostgreSQL dump created"
fi

docker compose exec -T nats nats --user= 2>/dev/null || true

docker compose ps --format json > "$BACKUP_DIR/container-state.json" 2>/dev/null || true

if command -v nats &>/dev/null; then
  nats stream ls --server=nats://localhost:4222 2>/dev/null > "$BACKUP_DIR/nats-streams.txt" || true
fi

echo "[backup] done: $BACKUP_DIR"
du -sh "$BACKUP_DIR"
