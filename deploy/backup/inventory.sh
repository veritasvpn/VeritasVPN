#!/usr/bin/env bash
set -euo pipefail

OUTDIR="${1:-./inventory-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUTDIR"

echo "[inventory] writing to $OUTDIR"
{
  echo "=== date ==="
  LC_ALL=C date
  echo "=== uname ==="
  uname -a
  echo "=== os-release ==="
  cat /etc/os-release 2>/dev/null || true
  echo "=== kernel ==="
  uname -r
  echo "=== architecture ==="
  dpkg --print-architecture 2>/dev/null || arch
  echo "=== docker version ==="
  docker version --format '{{.Server.Version}}' 2>/dev/null || echo "docker not available"
  echo "=== compose version ==="
  docker compose version --short 2>/dev/null || echo "compose not available"
  echo "=== free disk ==="
  df -h /
  echo "=== memory ==="
  free -h
  echo "=== temperature ==="
  vcgencmd measure_temp 2>/dev/null || cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null || echo "N/A"
} > "$OUTDIR/system-info.txt"

{
  echo "=== ip addr (redacted) ==="
  ip -br addr show 2>/dev/null || ip addr show
  echo "=== ip route ==="
  ip route
  echo "=== Tailscale IP ==="
  tailscale ip -4 2>/dev/null || echo "N/A"
  echo "=== WG show ==="
  wg show 2>/dev/null || echo "N/A"
  echo "=== ip forwarding ==="
  echo "ipv4: $(cat /proc/sys/net/ipv4/ip_forward)"
  echo "ipv6: $(cat /proc/sys/net/ipv6/conf/all/forwarding 2>/dev/null || echo N/A)"
} > "$OUTDIR/network-info.txt"

{
  echo "=== nftables ruleset ==="
  nft list ruleset 2>/dev/null || echo "nftables not active"
  echo "=== iptables rules ==="
  iptables-save 2>/dev/null || iptables -L -n -v
} > "$OUTDIR/firewall-info.txt"

{
  echo "=== systemd services (enabled) ==="
  systemctl list-unit-files --state=enabled 2>/dev/null || echo "systemd not available"
  echo "=== running containers ==="
  docker ps --format '{{.Image}} {{.ID}} {{.Names}} {{.Status}}' 2>/dev/null || echo "docker not available"
} > "$OUTDIR/services-info.txt"

docker compose config 2>/dev/null > "$OUTDIR/compose-config.yml" || true

echo "[inventory] done: $OUTDIR"
ls -la "$OUTDIR"
