#!/usr/bin/env bash
# Apply VeritasVPN SSH daemon hardening and fail2ban sshd jail on a k3s node.
set -Eeuo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "Run as root: sudo bash $0" >&2
  exit 1
fi

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
sshd_dropin=/etc/ssh/sshd_config.d/99-veritas-hardening.conf
fail2ban_jail=/etc/fail2ban/jail.d/veritas-sshd.local

install -d -o root -g root -m 0755 /etc/ssh/sshd_config.d
install -o root -g root -m 0644 \
  "${repo_root}/deploy/security/sshd-veritas-hardening.conf" \
  "${sshd_dropin}"

if ! sshd -t; then
  echo "sshd config test failed; leaving ${sshd_dropin} in place for inspection" >&2
  exit 1
fi

systemctl reload ssh.service

if ! command -v fail2ban-client >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update
    apt-get install -y fail2ban
  else
    echo "fail2ban not installed; sshd hardening applied without brute-force jail" >&2
    echo "VeritasVPN SSH hardening installed (sshd only)"
    exit 0
  fi
fi

install -d -o root -g root -m 0755 /etc/fail2ban/jail.d
install -o root -g root -m 0644 \
  "${repo_root}/deploy/security/fail2ban-sshd.local" \
  "${fail2ban_jail}"

systemctl enable --now fail2ban
systemctl reload fail2ban || systemctl restart fail2ban
fail2ban-client status sshd

echo "VeritasVPN SSH hardening installed (sshd + fail2ban)"
