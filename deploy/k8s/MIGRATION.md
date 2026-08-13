# VeritasVPN — k3s Migration Runbook

## Pre-migration checklist

- [ ] Full backup created and copied off-device (`deploy/backup/backup.sh`)
- [ ] Tailscale SSH is working (fallback access)
- [ ] Cloudflare dashboard accessible (to verify tunnel after cutover)
- [ ] Maintenance window scheduled (expect ~30 minutes)
- [ ] Router port forwarding confirmed: UDP 51820 → Pi LAN IP
- [ ] WireGuard server private key verified: `sudo cat /opt/veritasvpn/data/wireguard/private.key`

## Migration

### Option A: Automatic (recommended)

```bash
cd /opt/veritasvpn  # or wherever the repo is cloned
sudo bash deploy/k8s/scripts/migration/migrate.sh
```

The orchestrator runs all 9 steps sequentially, asking for confirmation at each.

### Option B: Manual (step by step)

Each script can be run independently:

```bash
sudo bash deploy/k8s/scripts/migration/01-preflight.sh        # Check environment
sudo bash deploy/k8s/scripts/migration/02-rotate-secrets.sh   # Rotate leaked creds
sudo bash deploy/k8s/scripts/migration/03-backup.sh           # Full backup
sudo bash deploy/k8s/scripts/migration/04-install-k3s.sh      # Install k3s
sudo bash deploy/k8s/scripts/migration/05-create-secrets.sh   # Create K8s secrets
sudo bash deploy/k8s/scripts/migration/06-migrate-postgres.sh # Migrate DB
sudo bash deploy/k8s/scripts/migration/07-wireguard-handoff.sh# Switch WireGuard owner
sudo bash deploy/k8s/scripts/migration/08-cutover.sh          # Stop compose, start k3s
sudo bash deploy/k8s/scripts/migration/09-validate.sh         # Verify
```

## Rollback

If anything fails during migration:

```bash
sudo bash deploy/k8s/scripts/migration/rollback.sh
```

This:
1. Stops the k3s agent (drops wg0)
2. Deletes k3s resources
3. Restarts docker compose
4. Re-runs WireGuard bootstrap

## Post-migration

After successful migration:

```bash
# Apply host firewall
sudo bash deploy/firewall/apply-rules.sh

# Install scheduled jobs
sudo cp deploy/cron/veritas-crontab /etc/cron.d/veritasvpn

# Verify monitoring works
bash deploy/monitoring/health-check.sh

# Reboot test
sudo reboot
# After reboot:
bash deploy/verify/boot-verify.sh

# Clean up compose leftovers (optional, after 24h validation)
docker system prune -a  # removes unused images/containers
```

## Updating K8s after code changes

```bash
# Build and push images to local registry
REGISTRY=localhost:31500 bash deploy/k8s/scripts/push-images.sh

# Apply prod overlay
kubectl apply -k deploy/k8s/overlays/prod/

# Or apply single service:
kubectl -n veritas rollout restart deploy/auth-svc
```

## Important paths (k3s)

| Path | Purpose |
|------|---------|
| `/var/lib/rancher/k3s/server/manifests/` | Auto-deploy manifests |
| `/etc/rancher/k3s/k3s.yaml` | kubeconfig (copy to ~/.kube/config) |
| `/var/lib/rancher/k3s/storage/` | local-path provisioner PVC data |
| `/opt/veritasvpn/data/wireguard/` | WireGuard keys (hostPath mount) |
