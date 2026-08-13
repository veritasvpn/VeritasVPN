#!/usr/bin/env bash
set -euo pipefail

echo "=== STEP 3: Full backup ==="
echo ""

REPO_ROOT="${REPO_ROOT:-/opt/veritasvpn}"

confirm() { read -rp "$1 [y/N] " yn; if [ "$yn" != "y" ] && [ "$yn" != "Y" ]; then echo "aborted"; exit 0; fi; }

confirm "Run full backup (WireGuard + PostgreSQL + configs)?"

BACKUP_DIR="./backups/pre-k3s-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"

cd "$REPO_ROOT"

echo "Backing up WireGuard..."
if [ -d data/wireguard ]; then
  cp -rp data/wireguard "$BACKUP_DIR/wireguard"
  chmod -R 700 "$BACKUP_DIR/wireguard"
fi

echo "Backing up PostgreSQL..."
docker compose exec -T postgres pg_dumpall -U veritas | gzip > "$BACKUP_DIR/pg_dumpall.sql.gz"
echo "  size: $(du -h "$BACKUP_DIR/pg_dumpall.sql.gz" | cut -f1)"

echo "Backing up .env..."
install -m 600 .env "$BACKUP_DIR/.env"

echo "Backing up compose config..."
docker compose config > "$BACKUP_DIR/compose-config.yml"

echo "Recording container images..."
docker compose images > "$BACKUP_DIR/container-images.txt"

echo "Backing up NATS data..."
if command -v nats &>/dev/null; then
  nats stream ls --server=nats://localhost:4222 2>/dev/null > "$BACKUP_DIR/nats-streams.txt" || true
fi

echo ""
echo "[backup] Done: $BACKUP_DIR"
echo "COPY THIS TO OFFLINE STORAGE before proceeding."
ls -lh "$BACKUP_DIR"/*

confirm "Backup is saved off-device?"
