#!/usr/bin/env bash
# Fail-fast local health audit for the Pi VPN and application stack.
set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-/home/jpg/VeritasVPN}"
WG_IFACE="${WG_IFACE:-wg0}"
WG_PORT="${WG_PORT:-51820}"
MAX_DISK_PERCENT="${MAX_DISK_PERCENT:-85}"
PUBLIC_HEALTH_URL="${PUBLIC_HEALTH_URL:-https://api.veritasvpn.cloud/healthz}"

required_containers=(
  veritasvpn-postgres-1
  veritasvpn-redis-1
  veritasvpn-nats-1
  veritasvpn-auth-svc-1
  veritasvpn-wg-manager-1
  veritasvpn-billing-svc-1
  veritasvpn-veritas-agent-1
  veritasvpn-nginx-1
  veritasvpn-cloudflared-1
)

ip link show "$WG_IFACE" >/dev/null
wg show "$WG_IFACE" >/dev/null
ss -H -lun | awk '{print $5}' | grep -Eq "(^|:)$WG_PORT$"
[[ "$(sysctl -n net.ipv4.ip_forward)" == "1" ]]
nft list table inet veritas_filter >/dev/null

for container in "${required_containers[@]}"; do
  [[ "$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null)" == "true" ]]
done

curl --fail --silent --show-error --max-time 10 "$PUBLIC_HEALTH_URL" >/dev/null

disk_percent="$(df --output=pcent "$PROJECT_DIR" | tail -n 1 | tr -dc '0-9')"
if (( disk_percent >= MAX_DISK_PERCENT )); then
  echo "Disk usage is ${disk_percent}% (limit ${MAX_DISK_PERCENT}%)" >&2
  exit 1
fi

logger -t veritas-vpn-health "VPN and application health checks passed"

