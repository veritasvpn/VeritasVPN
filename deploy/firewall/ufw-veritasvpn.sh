#!/usr/bin/env bash
set -euo pipefail

# Apply the Dell firewall policy. Run as root on the deployment host. The
# routed default is intentionally allowed because WireGuard and k3s both use
# forwarded traffic; host ingress remains deny-by-default.
if [[ "${EUID}" -ne 0 ]]; then
  echo "run as root" >&2
  exit 1
fi

cp -a /etc/default/ufw /etc/default/ufw.veritasvpn-prechange
sed -i 's/^IPV6=.*/IPV6=no/' /etc/default/ufw
ufw default deny incoming
ufw default allow outgoing
ufw default allow routed
ufw allow in on tailscale0 to any port 22 proto tcp comment 'Tailscale SSH'
ufw allow from 192.168.0.0/24 to any port 22 proto tcp comment 'LAN SSH'
ufw allow 51820/udp comment 'WireGuard'
ufw allow 80/tcp comment 'HTTP ingress'
ufw allow 443/tcp comment 'HTTPS ingress'
ufw allow from 192.168.0.0/24 to any port 6443 proto tcp comment 'LAN Kubernetes API'
ufw allow in on tailscale0 to any port 6443 proto tcp comment 'Tailscale Kubernetes API'
ufw allow from 192.168.0.0/24 to any port 31500 proto tcp comment 'LAN image registry'
ufw allow in on tailscale0 to any port 31500 proto tcp comment 'Tailscale image registry'
ufw --force enable
