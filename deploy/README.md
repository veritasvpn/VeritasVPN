# VeritasVPN production deployment

Production runs only on the Dell OptiPlex with K3s. The website is deployed independently by Cloudflare Pages. Raspberry Pi and Docker Compose instructions are retired; see `docs/DEPLOYMENT_SOURCE_OF_TRUTH.md`.

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
├── systemd/          # Dell K3s health, backup, drift, and hardware timers
└── verify/           # Post-deployment verification
    └── boot-verify.sh         # Verify WireGuard, forwarding, services after boot
```

## Controlled deployment on the Dell

```bash
cd /home/jpg/VeritasVPN
git status --short
sudo ./deploy/backup/backup-k3s.sh
sudo ./deploy/backup/restore-test.sh
sudo ./deploy/verify/production-readiness.sh
sudo ./deploy/k8s/scripts/apply.sh k3s
sudo ./deploy/verify/boot-verify.sh
```
