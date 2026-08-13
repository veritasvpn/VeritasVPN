#!/usr/bin/env bash
set -euo pipefail

export REPO_ROOT="$(cd "$(dirname "$0")/../../../.." &>/dev/null && pwd || echo "/opt/veritasvpn")"
MIGRATION_DIR="$(dirname "$0")"

echo "============================================"
echo "  VeritasVPN: Compose → k3s Migration"
echo "  Repo: $REPO_ROOT"
echo "============================================"
echo ""
echo "This will:"
echo "  1. Pre-flight check"
echo "  2. Rotate leaked secrets"
echo "  3. Full backup"
echo "  4. Install k3s alongside compose"
echo "  5. Create K8s secrets"
echo "  6. Migrate PostgreSQL data"
echo "  7. Hand off WireGuard to k3s agent"
echo "  8. Cut over tunnel + stop compose"
echo "  9. Validate"
echo ""
echo "Each step asks for confirmation. Ctrl+C to abort at any time."
echo ""

read -rp "Begin migration? [y/N] " yn
if [ "$yn" != "y" ] && [ "$yn" != "Y" ]; then exit 0; fi

for step in 01-preflight 02-rotate-secrets 03-backup 04-install-k3s 05-create-secrets 06-migrate-postgres 07-wireguard-handoff 08-cutover 09-validate; do
  bash "$MIGRATION_DIR/${step}.sh"
done

echo ""
echo "============================================"
echo "  Migration COMPLETE"
echo "============================================"
echo ""
echo "Next:"
echo "  - Apply firewall: sudo bash deploy/firewall/apply-rules.sh"
echo "  - Install cron: sudo cp deploy/cron/veritas-crontab /etc/cron.d/veritasvpn"
echo "  - Reboot test: sudo reboot && verify after boot"
