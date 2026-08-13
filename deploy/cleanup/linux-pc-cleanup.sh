#!/usr/bin/env bash
set -euo pipefail

echo "=== linuxDesktop Cleanup ==="
echo "This script disables obsolete VPN services on this PC."
echo "Run it AFTER the Raspberry Pi passes reboot and external VPN tests."
echo ""
read -rp "Continue? [y/N] " CONFIRM
if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
  echo "aborted"
  exit 0
fi

echo ""
echo "[cleanup] disabling openvpn.service"

if systemctl is-enabled openvpn.service &>/dev/null; then
  sudo systemctl disable --now openvpn.service
  echo "  openvpn.service disabled"
else
  echo "  openvpn.service already disabled or not found"
fi

echo ""
echo "[cleanup] checking docker"

if docker info &>/dev/null; then
  RUNNING=$(docker ps -q 2>/dev/null | wc -l)
  if [ "$RUNNING" -eq 0 ]; then
    echo "  no running containers"
    read -rp "  disable docker startup? [y/N] " DISABLE
    if [ "$DISABLE" = "y" ] || [ "$DISABLE" = "Y" ]; then
      sudo systemctl disable docker.socket docker.service
      echo "  docker.socket and docker.service disabled"
    fi
  else
    echo "  $RUNNING containers running — skipping docker disable"
  fi
else
  echo "  docker not running"
fi

echo ""
echo "[cleanup] removing old Veritas runtime data"
OLD_DIRS=(
  "/opt/veritasvpn"
  "$HOME/VeritasVPN/data/wireguard"
  "$HOME/.veritasvpn"
)
for d in "${OLD_DIRS[@]}"; do
  if [ -d "$d" ]; then
    echo "  found: $d"
    read -rp "  remove? [y/N] " RM
    if [ "$RM" = "y" ] || [ "$RM" = "Y" ]; then
      rm -rf "$d"
      echo "  removed $d"
    fi
  fi
done

echo ""
echo "[cleanup] checking for VPN listeners"
ss -lntup 2>/dev/null | grep -iE 'wireguard|openvpn|51820|1194' || echo "  no VPN listeners found"

echo ""
echo "[cleanup] checking Tailscale"
if command -v tailscale &>/dev/null && tailscale status &>/dev/null 2>&1; then
  echo "  Tailscale is active"
  read -rp "  leave Tailscale enabled? [Y/n] " TAIL
  if [ "$TAIL" = "n" ] || [ "$TAIL" = "N" ]; then
    sudo tailscale down
    sudo systemctl disable --now tailscaled
    echo "  tailscaled disabled"
  fi
else
  echo "  Tailscale not running"
fi

echo ""
echo "[cleanup] done. Reboot and verify no legacy VPN services start."
echo "  After reboot, run: ss -lntup | grep -iE 'wireguard|openvpn|51820|1194'"
