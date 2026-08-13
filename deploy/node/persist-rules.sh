#!/usr/bin/env bash
# Persist WireGuard sysctl and iptables rules across reboots.
# Run once as root during initial node setup.
set -euo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root: sudo $0" >&2
  exit 1
fi

echo "[persist] Ensuring ip_forward survives reboot..."
SYSCTL_FILE="/etc/sysctl.d/99-veritas-vpn.conf"
if [[ ! -f "$SYSCTL_FILE" ]]; then
  echo "net.ipv4.ip_forward = 1" > "$SYSCTL_FILE"
  echo "net.ipv6.conf.all.forwarding = 1" >> "$SYSCTL_FILE"
  sysctl -p "$SYSCTL_FILE"
  echo "[persist] sysctl drop-in created at $SYSCTL_FILE"
else
  echo "[persist] $SYSCTL_FILE already exists — skipping"
fi

echo ""
echo "[persist] Saving current iptables rules..."
if command -v iptables-save >/dev/null; then
  if command -v netfilter-persistent >/dev/null; then
    netfilter-persistent save
    echo "[persist] iptables rules saved via netfilter-persistent"
  elif [[ -d /etc/iptables ]]; then
    iptables-save > /etc/iptables/rules.v4
    ip6tables-save > /etc/iptables/rules.v6 2>/dev/null || true
    echo "[persist] iptables rules saved to /etc/iptables/"
  else
    mkdir -p /etc/iptables
    iptables-save > /etc/iptables/rules.v4
    ip6tables-save > /etc/iptables/rules.v6 2>/dev/null || true
    echo "[persist] /etc/iptables/ created and rules saved"
  fi
else
  echo "[persist] iptables not available — rules will be re-applied by veritas-agent on startup"
fi

echo ""
echo "[persist] If UFW is enabled, add these rules to allow WG traffic:"
echo "  sudo ufw allow 51820/udp comment 'WireGuard VPN'"
echo "  sudo ufw route allow in on wg0 out on \$(ip route show default | awk '{print \$5; exit}')"
echo ""
echo "[persist] Done. Rules will persist across reboots."
echo "  - sysctl: $SYSCTL_FILE"
echo "  - iptables: /etc/iptables/rules.v4 (if present)"
echo "  - veritas-agent re-applies rules on Docker container startup"
