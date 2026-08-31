#!/usr/bin/env bash
# Install JWT_SECRET cleanup as a user systemd timer (no root required).
# Uses jpg kubeconfig. Dell host systemd units still preferred when sudo is available.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
mkdir -p "$UNIT_DIR" "$HOME/.config/veritasvpn"

CUTOVER_FILE="$HOME/.config/veritasvpn/jwt-cutover-at"
if [[ ! -f "$CUTOVER_FILE" ]]; then
  printf '%s\n' '2026-08-31T18:42:00Z' >"$CUTOVER_FILE"
fi

cat >"$UNIT_DIR/veritas-jwt-secret-cleanup.service" <<EOF
[Unit]
Description=Delete JWT_SECRET from veritas-secrets after cutover drain (user)

[Service]
Type=oneshot
Environment=KUBECONFIG=%h/.kube/config
Environment=JWT_CUTOVER_AT_FILE=%h/.config/veritasvpn/jwt-cutover-at
ExecStart=${REPO_ROOT}/deploy/k8s/scripts/delete-jwt-secret-after.sh
EOF

cat >"$UNIT_DIR/veritas-jwt-secret-cleanup.timer" <<'EOF'
[Unit]
Description=Hourly JWT_SECRET drain cleanup

[Timer]
OnBootSec=10min
OnUnitActiveSec=1h
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now veritas-jwt-secret-cleanup.timer
# Linger so timer runs without interactive login
if command -v loginctl >/dev/null; then
  loginctl enable-linger "$(id -un)" 2>/dev/null || true
fi
systemctl --user list-timers 'veritas-*' --no-pager || true
printf 'User JWT cleanup timer enabled. Dry-run:\n'
KUBECONFIG="${KUBECONFIG:-$HOME/.kube/config}" \
  JWT_CUTOVER_AT_FILE="$CUTOVER_FILE" \
  "$REPO_ROOT/deploy/k8s/scripts/delete-jwt-secret-after.sh" --dry-run || true
