#!/usr/bin/env bash
# Minimal host firewall for a VeritasVPN Raspberry Pi node.
set -euo pipefail

WG_PORT="${WG_PORT:-51820}"
TAILSCALE_PORT="${TAILSCALE_PORT:-41641}"
ORIGIN_PORT="${ORIGIN_PORT:-8000}"
TUNNEL_RELAY_IP="${TUNNEL_RELAY_IP:-192.168.0.5}"
EGRESS_IFACE="${EGRESS_IFACE:-}"
LAN_SUBNET="${LAN_SUBNET:-}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root: sudo $0" >&2
  exit 1
fi

if [[ -z "$EGRESS_IFACE" ]]; then
  EGRESS_IFACE="$(ip -4 route show default | awk '{print $5; exit}')"
fi
if [[ -z "$EGRESS_IFACE" ]]; then
  echo "Could not determine the egress interface" >&2
  exit 1
fi

if [[ -z "$LAN_SUBNET" ]]; then
  LAN_SUBNET="$(ip -4 route show dev "$EGRESS_IFACE" proto kernel scope link | awk '{print $1; exit}')"
fi
if [[ -z "$LAN_SUBNET" ]]; then
  echo "Could not determine the LAN subnet" >&2
  exit 1
fi

RULESET="$(mktemp)"
trap 'rm -f "$RULESET"' EXIT

cat >"$RULESET" <<EOF
table inet veritas_filter {
  set cloudflare_ipv4 {
    type ipv4_addr
    flags interval
    elements = {
      173.245.48.0/20, 103.21.244.0/22, 103.22.200.0/22,
      103.31.4.0/22, 141.101.64.0/18, 108.162.192.0/18,
      190.93.240.0/20, 188.114.96.0/20, 197.234.240.0/22,
      198.41.128.0/17, 162.158.0.0/15, 104.16.0.0/13,
      104.24.0.0/14, 172.64.0.0/13, 131.0.72.0/22
    }
  }

  chain input {
    type filter hook input priority -20; policy accept;

    ct state established,related accept
    iifname "lo" accept
    iifname "tailscale0" accept

    # Keep Tailscale and WireGuard reachable from the internet.
    iifname "$EGRESS_IFACE" udp dport $TAILSCALE_PORT accept
    iifname "$EGRESS_IFACE" udp dport $WG_PORT accept

    # Preserve DHCP, diagnostics, and LAN-only recovery SSH.
    iifname "$EGRESS_IFACE" udp sport 67 udp dport 68 accept
    iifname "$EGRESS_IFACE" ip protocol icmp accept
    iifname "$EGRESS_IFACE" ip saddr $LAN_SUBNET tcp dport 22 accept
    iifname "$EGRESS_IFACE" ip saddr @cloudflare_ipv4 tcp dport $ORIGIN_PORT accept
    iifname "$EGRESS_IFACE" ip saddr $TUNNEL_RELAY_IP tcp dport $ORIGIN_PORT accept

    # Reject every other unsolicited packet arriving from Ethernet.
    iifname "$EGRESS_IFACE" counter drop
  }

  chain forward {
    type filter hook forward priority -20; policy accept;

    ct state established,related accept
    iifname "wg0" oifname "$EGRESS_IFACE" accept
    iifname "$EGRESS_IFACE" oifname "wg0" accept

    # Until api.veritasvpn.cloud is migrated to Tunnel ingress, allow the
    # published origin only from Cloudflare's authoritative proxy ranges.
    iifname "$EGRESS_IFACE" ip saddr @cloudflare_ipv4 tcp dport $ORIGIN_PORT accept
    iifname "$EGRESS_IFACE" ip saddr $TUNNEL_RELAY_IP tcp dport $ORIGIN_PORT accept
    iifname "$EGRESS_IFACE" tcp dport $ORIGIN_PORT counter drop
  }
}
EOF

if [[ "${CHECK_ONLY:-0}" == "1" ]]; then
  nft --check --file "$RULESET"
  echo "Firewall rules validated for egress=$EGRESS_IFACE lan=$LAN_SUBNET"
  exit 0
fi

nft delete table inet veritas_filter 2>/dev/null || true
nft -f "$RULESET"
nft list table inet veritas_filter
