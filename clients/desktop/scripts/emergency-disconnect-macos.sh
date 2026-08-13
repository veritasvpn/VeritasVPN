#!/bin/bash
# Emergency restore if VeritasVPN WireGuard left the Mac without internet.
# Usage: sudo bash clients/desktop/scripts/emergency-disconnect-macos.sh
set -uo pipefail

echo "[veritas] removing tunnel split-default routes…"
route -n delete -net 0.0.0.0/1 2>/dev/null || true
route -n delete -net 128.0.0.0/1 2>/dev/null || true

STATE="$HOME/Library/Application Support/cloud.veritasvpn.desktop"
if [[ -f "$STATE/iface.meta" ]]; then
  # shellcheck disable=SC1090
  source "$STATE/iface.meta" 2>/dev/null || true
  if [[ -n "${endpoint_ip:-}" ]]; then
    echo "[veritas] removing endpoint host route $endpoint_ip…"
    route -n delete -host "$endpoint_ip" 2>/dev/null || true
  fi
  if [[ -n "${iface:-}" ]]; then
    ifconfig "$iface" down 2>/dev/null || true
  fi
  if [[ -n "${service:-}" ]]; then
    networksetup -setdnsservers "$service" Empty 2>/dev/null || true
  fi
fi
if [[ -f "$STATE/iface" ]]; then
  IFACE="$(cat "$STATE/iface")"
  route -n delete -net 0.0.0.0/1 -interface "$IFACE" 2>/dev/null || true
  route -n delete -net 128.0.0.0/1 -interface "$IFACE" 2>/dev/null || true
  ifconfig "$IFACE" down 2>/dev/null || true
fi
if [[ -f "$STATE/wireguard-go.pid" ]]; then
  kill "$(cat "$STATE/wireguard-go.pid")" 2>/dev/null || true
  kill -9 "$(cat "$STATE/wireguard-go.pid")" 2>/dev/null || true
fi

echo "[veritas] killing wireguard-go…"
pkill -f '/wireguard-go utun' 2>/dev/null || true
rm -f /var/run/wireguard/*.sock 2>/dev/null || true
rm -f "$STATE/iface" "$STATE/iface.meta" "$STATE/wireguard-go.pid" "$STATE/peer_id" 2>/dev/null || true

for S in "Wi-Fi" "Ethernet" "Thunderbolt Ethernet" "USB 10/100/1000 LAN"; do
  networksetup -setdnsservers "$S" Empty 2>/dev/null || true
done

echo "[veritas] done. Default route now:"
route -n get default 2>/dev/null | egrep 'gateway|interface' || true
