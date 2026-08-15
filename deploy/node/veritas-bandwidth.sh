#!/usr/bin/env bash
# Apply an independent 50 Mbps ceiling to every IPv4 /32 WireGuard peer.
# The script is idempotent and only rebuilds qdiscs when the peer set changes.
set -euo pipefail

WG_IFACE="${WG_IFACE:-wg0}"
DEVICE_RATE="${VERITAS_DEVICE_RATE:-50mbit}"
STATE_FILE="${VERITAS_BANDWIDTH_STATE:-/run/veritas-bandwidth.peers}"
TC="${TC_BIN:-/sbin/tc}"
WG="${WG_BIN:-/usr/bin/wg}"
IP="${IP_BIN:-/sbin/ip}"

if [[ ! -x "$TC" || ! -x "$WG" || ! -x "$IP" ]]; then
  echo "required networking tools are unavailable" >&2
  exit 1
fi

if ! "$IP" link show dev "$WG_IFACE" >/dev/null 2>&1; then
  exit 0
fi

mapfile -t peers < <(
  "$WG" show "$WG_IFACE" allowed-ips 2>/dev/null |
    awk '{
      for (i = 2; i <= NF; i++) {
        count = split($i, entries, ",")
        for (j = 1; j <= count; j++)
          if (entries[j] ~ /^[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+[/]32$/) print entries[j]
      }
    }' | sort -u
)

desired=""
if (("${#peers[@]}")); then
  desired="$(printf '%s\n' "${peers[@]}")"
fi
current="$(cat "$STATE_FILE" 2>/dev/null || true)"

# Avoid resetting live queues every timer tick. A reset is only needed when
# peers change or if another service removed the shaping qdiscs.
if [[ "$desired" == "$current" ]] &&
   "$TC" qdisc show dev "$WG_IFACE" | grep -q 'qdisc htb 1:' &&
   "$TC" qdisc show dev "$WG_IFACE" | grep -q 'qdisc ingress ffff:'; then
  exit 0
fi

# Egress (server -> VPN device): HTB class/filter per peer, with fq_codel
# leaves for fair queueing inside each 50 Mbps class.
"$TC" qdisc del dev "$WG_IFACE" root 2>/dev/null || true
"$TC" qdisc add dev "$WG_IFACE" root handle 1: htb default 999 r2q 100
"$TC" class replace dev "$WG_IFACE" parent 1: classid 1:999 htb rate 1gbit ceil 1gbit quantum 1514

# Ingress (VPN device -> server): police each peer's source /32 at 50 Mbps.
"$TC" qdisc del dev "$WG_IFACE" ingress 2>/dev/null || true
"$TC" qdisc add dev "$WG_IFACE" handle ffff: ingress

priority=10
for peer in "${peers[@]}"; do
  # Peers are sorted above; sequential IDs avoid collisions across subnets.
  minor="$((priority + 1000))"

  "$TC" class replace dev "$WG_IFACE" parent 1: classid "1:${minor}" htb rate "$DEVICE_RATE" ceil "$DEVICE_RATE"
  "$TC" qdisc replace dev "$WG_IFACE" parent "1:${minor}" handle "${minor}:" fq_codel
  "$TC" filter replace dev "$WG_IFACE" protocol ip parent 1: prio "$priority" u32 match ip dst "$peer" flowid "1:${minor}"
  "$TC" filter replace dev "$WG_IFACE" protocol ip parent ffff: prio "$priority" u32 match ip src "$peer" police rate "$DEVICE_RATE" burst 100k drop
  priority="$((priority + 1))"
done

install -d -m 0755 "$(dirname "$STATE_FILE")"
printf '%s\n' "$desired" > "$STATE_FILE"
echo "applied ${#peers[@]} WireGuard peer bandwidth cap(s) at $DEVICE_RATE"
