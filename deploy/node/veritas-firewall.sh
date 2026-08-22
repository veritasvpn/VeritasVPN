#!/usr/bin/env bash
# Host firewall for a VeritasVPN k3s/WireGuard node.
# Coexists with the agent-owned inet veritas table (NAT + VPN isolation).
set -euo pipefail

WG_PORT="${WG_PORT:-51820}"
WG_INTERFACE="${WG_INTERFACE:-wg0}"
VPN_DNS_ADDRESS="${VPN_DNS_ADDRESS:-10.0.0.1}"
TAILSCALE_PORT="${TAILSCALE_PORT:-41641}"
EGRESS_IFACE="${EGRESS_IFACE:-}"
LAN_SUBNET="${LAN_SUBNET:-}"
K3S_FLANNEL_PORT="${K3S_FLANNEL_PORT:-8472}"
ALLOW_LAN_SSH="${ALLOW_LAN_SSH:-1}"
ALLOW_LAN_K3S_API="${ALLOW_LAN_K3S_API:-1}"

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

LAN_SSH_RULE=""
if [[ "$ALLOW_LAN_SSH" == "1" ]]; then
  LAN_SSH_RULE="iifname \"$EGRESS_IFACE\" ip saddr $LAN_SUBNET tcp dport 22 accept"
fi

LAN_K3S_RULE=""
if [[ "$ALLOW_LAN_K3S_API" == "1" ]]; then
  LAN_K3S_RULE="iifname \"$EGRESS_IFACE\" ip saddr $LAN_SUBNET tcp dport 6443 accept"
fi

cat >"$RULESET" <<EOF
table inet veritas_filter {
  chain input {
    type filter hook input priority -10; policy drop;

    ct state invalid drop
    ct state established,related accept
    iifname "lo" accept
    # Treat the Tailnet as an administration network, not a trusted LAN.  The
    # Tailscale iptables chain otherwise accepts every host port before UFW
    # can filter it, so allow only the services intentionally administered
    # over Tailscale.  The local tailscaled listener is retained for transport.
    iifname "tailscale0" tcp dport { 22, 6443, 31500, 64462 } accept
    iifname "tailscale0" counter drop
    iifname "wg0" accept
    iifname "cni0" accept
    iifname "flannel.1" accept
    iifname "docker0" accept
    iifname "br-*" accept

    # Public VPN + Tailscale data plane.
    iifname "$EGRESS_IFACE" udp dport $WG_PORT accept
    iifname "$EGRESS_IFACE" udp dport $TAILSCALE_PORT accept

    # DHCP client + limited ICMP diagnostics on the LAN uplink.
    iifname "$EGRESS_IFACE" udp sport 67 udp dport 68 accept
    iifname "$EGRESS_IFACE" ip protocol icmp icmp type { echo-request, destination-unreachable, time-exceeded } accept

    $LAN_SSH_RULE
    $LAN_K3S_RULE

    # Explicitly deny management/metrics on the uplink before the catch-all.
    iifname "$EGRESS_IFACE" tcp dport { 22, 9090, 9100, 10250, 10255, 6443, 31500, 5000, 2375, 2376 } counter drop
    iifname "$EGRESS_IFACE" udp dport { 9090, 9100 } counter drop

    # Drop unsolicited WAN/LAN traffic (SSH from WAN, kubelet, registry, ...).
    iifname "$EGRESS_IFACE" counter drop
  }

  chain forward {
    type filter hook forward priority -10; policy accept;

    # Agent table veritas (priority filter/0) enforces VPN isolation.
    # Keep k8s CNI paths unrestricted here.
    ct state established,related accept
    iifname "cni0" accept
    oifname "cni0" accept
    iifname "flannel.1" accept
    oifname "flannel.1" accept
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

# UFW evaluates later in the INPUT hook than this table. Its default-deny policy
# would otherwise drop DNS queries to the WireGuard gateway before the DNS agent.
# Keep the persistent exception limited to the VPN gateway DNS listener.
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "^Status: active$"; then
  ufw allow in on "$WG_INTERFACE" to "$VPN_DNS_ADDRESS" port 53 proto udp comment "Veritas VPN DNS"
  ufw allow in on "$WG_INTERFACE" to "$VPN_DNS_ADDRESS" port 53 proto tcp comment "Veritas VPN DNS"
fi

nft list table inet veritas_filter
echo "veritas_filter applied egress=$EGRESS_IFACE lan=$LAN_SUBNET wg=$WG_PORT dns=$VPN_DNS_ADDRESS"
