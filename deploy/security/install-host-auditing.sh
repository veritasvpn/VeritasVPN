#!/usr/bin/env bash
set -Eeuo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "run as root" >&2
  exit 1
fi

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
install -d -o root -g root -m 0750 /var/lib/rancher/k3s/server/logs
install -o root -g root -m 0600 \
  "${repo_root}/deploy/security/k3s-audit-policy.yaml" \
  /var/lib/rancher/k3s/server/audit.yaml
install -o root -g root -m 0640 \
  "${repo_root}/deploy/security/auditd-veritas.rules" \
  /etc/audit/rules.d/50-veritasvpn.rules
install -d -o root -g root -m 0755 /etc/systemd/system/k3s.service.d
install -o root -g root -m 0644 \
  "${repo_root}/deploy/security/k3s-hardening.conf" \
  /etc/systemd/system/k3s.service.d/10-veritas-hardening.conf
augenrules --load
systemctl daemon-reload
systemctl enable --now auditd

echo "VeritasVPN host audit rules installed"
