#!/usr/bin/env bash
# Bootstrap a single VeritasVPN WireGuard node (linuxDesktop today, VPS later).
set -euo pipefail

WG_IFACE="${WG_IFACE:-wg0}"
WG_PORT="${WG_PORT:-51820}"
WG_SUBNET="${WG_SUBNET:-10.0.0.0/24}"
WG_ADDR="${WG_ADDR:-10.0.0.1/24}"
EGRESS_IFACE="${EGRESS_IFACE:-}"
KEY_DIR="${KEY_DIR:-/etc/wireguard}"
KEY_FILE="${KEY_FILE:-$KEY_DIR/private.key}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root: sudo $0" >&2
  exit 1
fi

if [[ -z "$EGRESS_IFACE" ]]; then
  EGRESS_IFACE="$(ip route show default | awk '{print $5; exit}')"
fi
if [[ -z "$EGRESS_IFACE" ]]; then
  echo "Could not detect egress interface; set EGRESS_IFACE" >&2
  exit 1
fi

echo "[bootstrap] iface=$WG_IFACE addr=$WG_ADDR port=$WG_PORT egress=$EGRESS_IFACE"

# Persist IP forwarding across reboots
SYSCTL_CONF="/etc/sysctl.d/99-veritas-vpn.conf"
if [[ ! -f "$SYSCTL_CONF" ]]; then
  echo "net.ipv4.ip_forward = 1" > "$SYSCTL_CONF"
  echo "net.ipv6.conf.all.forwarding = 1" >> "$SYSCTL_CONF"
fi
sysctl -w net.ipv4.ip_forward=1 >/dev/null
mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"

if [[ ! -f "$KEY_FILE" ]]; then
  if ip link show "$WG_IFACE" >/dev/null 2>&1 && command -v wg >/dev/null; then
    wg show "$WG_IFACE" private-key > "$KEY_FILE" || true
  fi
fi
if [[ ! -s "$KEY_FILE" ]]; then
  umask 077
  wg genkey > "$KEY_FILE"
fi
chmod 600 "$KEY_FILE"
PUB_KEY="$(wg pubkey < "$KEY_FILE")"

if ! ip link show "$WG_IFACE" >/dev/null 2>&1; then
  ip link add dev "$WG_IFACE" type wireguard
fi

ip addr replace "$WG_ADDR" dev "$WG_IFACE"
wg set "$WG_IFACE" private-key "$KEY_FILE" listen-port "$WG_PORT"
ip link set up dev "$WG_IFACE"

# NAT for VPN clients (idempotent).
if command -v iptables >/dev/null; then
  iptables -t nat -C POSTROUTING -s "$WG_SUBNET" -o "$EGRESS_IFACE" -j MASQUERADE 2>/dev/null \
    || iptables -t nat -A POSTROUTING -s "$WG_SUBNET" -o "$EGRESS_IFACE" -j MASQUERADE
  iptables -C FORWARD -i "$WG_IFACE" -j ACCEPT 2>/dev/null \
    || iptables -A FORWARD -i "$WG_IFACE" -j ACCEPT
  iptables -C FORWARD -o "$WG_IFACE" -j ACCEPT 2>/dev/null \
    || iptables -A FORWARD -o "$WG_IFACE" -j ACCEPT

  iptables -C FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null \
    || iptables -I FORWARD 1 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
fi

echo "[bootstrap] ready"
echo "  public_key=$PUB_KEY"
echo "  endpoint=<PUBLIC_IP>:$WG_PORT"
echo "  Forward UDP $WG_PORT on your router to this host for remote clients."
echo ""
echo "  If using UFW, run:"
echo "    sudo ufw allow $WG_PORT/udp comment 'WireGuard VPN'"
echo "    sudo ufw route allow in on wg0 out on $EGRESS_IFACE"
