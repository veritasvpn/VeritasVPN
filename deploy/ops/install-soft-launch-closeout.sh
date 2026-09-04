#!/usr/bin/env bash
# Idempotent production host install for soft-launch closeout timers + firewall refresh.
# Run as root on the production node (production node).
set -euo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  printf 'Run as root: sudo %s\n' "$0" >&2
  exit 2
fi

REPO_ROOT="${REPO_ROOT:-/opt/veritasvpn}"
if [[ ! -d "$REPO_ROOT/.git" ]]; then
  printf 'REPO_ROOT missing git checkout: %s\n' "$REPO_ROOT" >&2
  exit 2
fi

install -d -m 0750 /etc/veritasvpn
E2E_ENV=/etc/veritasvpn/e2e.env
if [[ ! -f "$E2E_ENV" ]]; then
  umask 077
  cat >"$E2E_ENV" <<'EOF'
# Premium synthetic account used by tunnel-hold + optional local smoke.
# Populate once: VERITAS_E2E_ACCOUNT_ID=...
# Match GitHub Actions secret VERITAS_E2E_ACCOUNT_ID.
VERITAS_E2E_ACCOUNT_ID=
EOF
  chmod 0600 "$E2E_ENV"
  chown root:root "$E2E_ENV"
  printf 'Created %s — set VERITAS_E2E_ACCOUNT_ID before enabling tunnel-hold.\n' "$E2E_ENV"
fi

CUTOVER_FILE=/etc/veritasvpn/jwt-cutover-at
if [[ ! -f "$CUTOVER_FILE" ]]; then
  printf '%s\n' '2026-08-31T18:42:00Z' >"$CUTOVER_FILE"
  chmod 0644 "$CUTOVER_FILE"
  printf 'Wrote %s\n' "$CUTOVER_FILE"
fi

# Firewall: reinstall if drifted from repo.
install -m 0755 "$REPO_ROOT/deploy/node/veritas-firewall.sh" /usr/local/sbin/veritas-firewall
install -m 0644 "$REPO_ROOT/deploy/node/veritas-firewall.service" /etc/systemd/system/veritas-firewall.service
systemctl daemon-reload
systemctl enable --now veritas-firewall.service
systemctl restart veritas-firewall.service

# Tunnel hold
install -m 0644 "$REPO_ROOT/deploy/systemd/veritas-tunnel-hold.service" /etc/systemd/system/
install -m 0644 "$REPO_ROOT/deploy/systemd/veritas-tunnel-hold.timer" /etc/systemd/system/
# JWT cleanup
install -m 0755 "$REPO_ROOT/deploy/k8s/scripts/delete-jwt-secret-after.sh" /usr/local/sbin/veritas-delete-jwt-secret-after
# Point unit at /usr/local/sbin copy for stability if repo path moves
cat >/etc/systemd/system/veritas-jwt-secret-cleanup.service <<'EOF'
[Unit]
Description=Delete JWT_SECRET from veritas-secrets after cutover drain window
After=network-online.target k3s.service
Wants=network-online.target

[Service]
Type=oneshot
Environment=KUBECONFIG=/etc/rancher/k3s/k3s.yaml
Environment=JWT_CUTOVER_AT_FILE=/etc/veritasvpn/jwt-cutover-at
# Script runs as root; kubectl uses the node kubeconfig.
User=root
ExecStart=/usr/local/sbin/veritas-delete-jwt-secret-after
StandardOutput=journal
StandardError=journal
EOF
install -m 0644 "$REPO_ROOT/deploy/systemd/veritas-jwt-secret-cleanup.timer" /etc/systemd/system/
# Fix tunnel-hold ExecStart to absolute repo path
sed "s|/opt/veritasvpn|${REPO_ROOT}|g" \
  "$REPO_ROOT/deploy/systemd/veritas-tunnel-hold.service" \
  >/etc/systemd/system/veritas-tunnel-hold.service

systemctl daemon-reload
systemctl enable --now veritas-jwt-secret-cleanup.timer

if grep -qE '^VERITAS_E2E_ACCOUNT_ID=.+' "$E2E_ENV"; then
  systemctl enable --now veritas-tunnel-hold.timer
  printf 'Enabled veritas-tunnel-hold.timer\n'
else
  systemctl disable --now veritas-tunnel-hold.timer 2>/dev/null || true
  printf 'Tunnel-hold timer NOT enabled — set VERITAS_E2E_ACCOUNT_ID in %s then:\n' "$E2E_ENV"
  printf '  systemctl enable --now veritas-tunnel-hold.timer\n'
fi

chmod +x "$REPO_ROOT/deploy/ops/"*.sh "$REPO_ROOT/deploy/verify/"*.sh \
  "$REPO_ROOT/deploy/k8s/scripts/delete-jwt-secret-after.sh" 2>/dev/null || true

# After rolling wg-manager, restart the agent so it re-registers with the current
# image pin (stale agent: heartbeat 401 + wg_port 51820 breaks WAN E2E):
#   kubectl -n veritas delete pod -l app=veritas-agent
#   bash deploy/ops/verify-agent-health.sh

systemctl list-timers 'veritas-*' --no-pager 2>/dev/null || true
printf '\nNext: bash %s/deploy/ops/verify-acao.sh\n' "$REPO_ROOT"
printf '      bash %s/deploy/ops/verify-agent-health.sh\n' "$REPO_ROOT"
printf '      PUBLIC_IP=… bash %s/deploy/ops/verify-ssh-wan.sh  (from off-LAN / GHA)\n' "$REPO_ROOT"
