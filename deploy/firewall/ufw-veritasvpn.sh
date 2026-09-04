#!/usr/bin/env bash
set -euo pipefail

# Apply the production host firewall policy. Run as root on the deployment host. The
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
# Cloudflare Tunnel creates outbound connections from this host.  Nothing
# should reach the production host directly over HTTP(S), so actively remove legacy
# public exceptions whenever this policy is re-applied.
ufw delete allow 80/tcp >/dev/null 2>&1 || true
ufw delete allow 443/tcp >/dev/null 2>&1 || true
ufw allow from 192.168.0.0/24 to any port 6443 proto tcp comment 'LAN Kubernetes API'
ufw allow in on tailscale0 to any port 6443 proto tcp comment 'Tailscale Kubernetes API'
ufw delete allow from 192.168.0.0/24 to any port 31500 proto tcp >/dev/null 2>&1 || true
# Registry NodePort removed; use kubectl port-forward on loopback.
ufw --force enable
