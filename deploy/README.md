# VeritasVPN Production Deployment

## Directory Layout

```
deploy/
├── backup/           # Inventory and backup scripts
│   ├── inventory.sh          # Capture system state snapshot
│   ├── backup.sh              # Full backup (WireGuard + PostgreSQL + configs)
│   ├── backup-postgres.sh     # Automated pg_dump with retention
│   └── restore-postgres.sh    # Restore PostgreSQL from backup file
├── cleanup/          # Linux PC cleanup
│   └── linux-pc-cleanup.sh    # Remove legacy VPN services from desktop
├── cloudflare/       # Tunnel documentation
│   └── tunnel-config.md       # Cloudflare ingress rules and WAF config
├── cron/             # Scheduled jobs
│   └── veritas-crontab        # Backup, health check, verification timers
├── docker/           # Docker daemon config
│   └── daemon.json            # Log rotation and ulimits
├── firewall/         # Host firewall
│   ├── nftables.conf          # nftables rules (input, forward, NAT)
│   └── apply-rules.sh         # Safe rollout with auto-rollback
├── k8s/              # Kubernetes manifests (existing)
├── monitoring/       # Health monitoring
│   └── health-check.sh        # Full-stack health probe
├── nginx/            # Production nginx config
│   └── nginx.prod.conf        # Rate-limited reverse proxy with security headers
├── node/             # Bare-metal node bootstrap (existing)
├── recovery/         # Disaster recovery
│   └── recovery-runbook.md    # Step-by-step bare-metal recovery
├── security/         # Hardening configs
│   ├── inventory-secrets.sh   # Audit credentials without printing values
│   ├── create-db-users.sh     # Create per-service PostgreSQL users
│   ├── redis-acl.conf         # Redis ACL rules
│   ├── nats-server.conf       # NATS auth and limits
│   └── .env.prod.example      # Production env template
├── systemd/          # Systemd units
│   └── veritasvpn.service     # Docker Compose stack as systemd service
└── verify/           # Post-deployment verification
    └── boot-verify.sh         # Verify WireGuard, forwarding, services after boot
```

## Quick Start (on Raspberry Pi)

```bash
cd /opt/veritasvpn

# 1. Capture current state
sudo bash deploy/backup/inventory.sh

# 2. Run full backup
bash deploy/backup/backup.sh

# 3. Start production stack
make prod-up

# 4. Apply firewall (has auto-rollback)
sudo bash deploy/firewall/apply-rules.sh

# 5. Verify
bash deploy/verify/boot-verify.sh
bash deploy/monitoring/health-check.sh

# 6. Enable on boot
sudo cp deploy/systemd/veritasvpn.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now veritasvpn

# 7. Install cron jobs
sudo cp deploy/cron/veritas-crontab /etc/cron.d/veritasvpn
```
