#!/usr/bin/env bash
# Apply VeritasVPN SSH daemon hardening and fail2ban sshd jail on a k3s node.
set -Eeuo pipefail

if [[ ${EUID} -ne 0 ]]; then
  echo "Run as root: sudo bash $0" >&2
  exit 1
fi

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
sshd_dropin=/etc/ssh/sshd_config.d/00-veritas-hardening.conf
legacy_sshd_dropins=(
  /etc/ssh/sshd_config.d/01-veritasvpn-hardening.conf
  /etc/ssh/sshd_config.d/99-veritas-hardening.conf
)
fail2ban_jail=/etc/fail2ban/jail.d/veritas-sshd.local

install -d -o root -g root -m 0755 /etc/ssh/sshd_config.d

# sshd uses the first value for each keyword. Drop-ins are loaded in name order,
# so 00-veritas-hardening.conf must sort before 50-cloud-init.conf.
for legacy in "${legacy_sshd_dropins[@]}"; do
  if [[ -f "${legacy}" ]]; then
    rm -f "${legacy}"
    echo "Removed superseded ${legacy}"
  fi
done
if compgen -G /etc/ssh/sshd_config.d/01-veritasvpn-hardening.conf.bak* >/dev/null; then
  : # keep archived backups
fi

install -o root -g root -m 0644 \
  "${repo_root}/deploy/security/sshd-veritas-hardening.conf" \
  "${sshd_dropin}"

if ! sshd -t; then
  echo "sshd config test failed; leaving ${sshd_dropin} in place for inspection" >&2
  exit 1
fi

systemctl reload ssh.service

if [[ "$(sshd -T | awk '/^maxauthtries/{print $2; exit}')" != "3" ]]; then
  echo "WARN: maxauthtries is not 3 after reload; inspect /etc/ssh/sshd_config.d/*.conf" >&2
fi
if [[ "$(sshd -T | awk '/^passwordauthentication/{print $2; exit}')" != "no" ]]; then
  echo "WARN: passwordauthentication is not no after reload; inspect /etc/ssh/sshd_config.d/*.conf" >&2
fi

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
