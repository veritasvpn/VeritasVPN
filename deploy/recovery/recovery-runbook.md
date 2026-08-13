# VeritasVPN Bare-Metal Recovery Runbook

## Prerequisites

- Raspberry Pi OS (Bookworm or later)
- Docker Engine and Docker Compose plugin installed
- A recent encrypted backup archive
- SSH access via Tailscale or LAN management subnet

## Recovery Steps

### 1. Flash a clean OS to the Pi

Use Raspberry Pi Imager. Enable SSH and set a strong password (not default).

### 2. Install dependencies

```bash
sudo apt update && sudo apt install -y \
  docker.io docker-compose-v2 wireguard-tools git
sudo usermod -aG docker $USER
```

### 3. Clone the repository

```bash
git clone https://github.com/anomalyco/VeritasVPN /opt/veritasvpn
cd /opt/veritasvpn
```

### 4. Restore secrets

Decrypt the backup archive and copy:

```bash
cp <backup>/.env .env
chmod 600 .env
cp -r <backup>/wireguard data/wireguard
chmod -R 700 data/wireguard
```

### 5. Restore PostgreSQL

```bash
docker compose up -d postgres
sleep 10
gunzip -c <backup>/pg_dumpall.sql.gz | docker compose exec -T postgres psql -U veritas
```

### 6. Start the stack

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

### 7. Apply firewall rules

```bash
sudo ./deploy/firewall/apply-rules.sh
```

### 8. WireGuard bootstrap (if agent cannot recover wg0)

```bash
sudo ./deploy/node/bootstrap-wg.sh
sudo ./deploy/node/persist-rules.sh
```

### 9. Verify

```bash
./deploy/verify/boot-verify.sh
wg show
docker compose ps
tailscale status
```

### 10. Enable automatic start on boot

```bash
sudo cp deploy/systemd/veritasvpn.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now veritasvpn
```

## Critical Locations

| Path | Content |
|------|---------|
| `.env` | Production secrets (JWT, tokens, passwords) |
| `data/wireguard/` | WireGuard server private key and peer configs |
| `docker-compose.yml` | Base service definitions |
| `docker-compose.prod.yml` | Production overrides |
| `deploy/firewall/` | nftables rules and apply script |
| `pgdata` (Docker volume) | PostgreSQL data directory |

## Contacts

- Tailscale SSH for remote access
- Cloudflare Tunnel for public HTTP
- Document current router forwarding rules externally
