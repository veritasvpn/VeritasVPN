#!/usr/bin/env bash
# Daily root-only recovery snapshot for the single-node Pi deployment.
set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-/opt/veritasvpn}"
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/veritasvpn/automatic}"
RETENTION_DAYS="${RETENTION_DAYS:-35}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-veritasvpn-postgres-1}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST="$BACKUP_ROOT/$STAMP"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root: sudo $0" >&2
  exit 1
fi

install -d -m 700 "$BACKUP_ROOT" "$DEST" "$DEST/config"

docker exec "$POSTGRES_CONTAINER" pg_dump -U veritas -d veritas -Fc >"$DEST/postgres.dump"
test -s "$DEST/postgres.dump"
docker exec -i "$POSTGRES_CONTAINER" pg_restore -l <"$DEST/postgres.dump" >/dev/null

install -m 600 "$PROJECT_DIR/.env" "$DEST/config/.env"
install -m 600 "$PROJECT_DIR/docker-compose.yml" "$DEST/config/docker-compose.yml"
install -m 600 "$PROJECT_DIR/docker-compose.pi.yml" "$DEST/config/docker-compose.pi.yml"
install -m 600 "$PROJECT_DIR/website/nginx.conf" "$DEST/config/nginx.conf"
cp -a "$PROJECT_DIR/data/wireguard" "$DEST/wireguard"
wg showconf wg0 >"$DEST/wg0.showconf"
nft list ruleset >"$DEST/nftables.rules"
docker ps --no-trunc --format '{{.Names}}|{{.Image}}|{{.ID}}|{{.Status}}' >"$DEST/containers.txt"
chmod -R go-rwx "$DEST"

find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -mtime "+$RETENTION_DAYS" -exec rm -rf -- {} +
logger -t veritas-backup "Backup completed: $DEST"

