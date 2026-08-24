#!/usr/bin/env bash
# Per-peer 100 Mbps cap on WireGuard.
# Download: HTB on wg0 (server -> client).
# Upload: IFB + HTB (no ingress police drop; that was starving TCP ACKs ~50 Mbps).
set -euo pipefail

WG_IFACE="${WG_IFACE:-wg0}"
IFB_IFACE="${VERITAS_IFB_IFACE:-ifb-veritas}"
DEVICE_RATE="${VERITAS_DEVICE_RATE:-100mbit}"
STATE_FILE="${VERITAS_BANDWIDTH_STATE:-/run/veritas-bandwidth.peers}"
TC="${TC_BIN:-/sbin/tc}"
WG="${WG_BIN:-/usr/bin/wg}"
IP="${IP_BIN:-/sbin/ip}"
SHAPE_VERSION="v4-100mbit-ifb"

if [[ ! -x "$TC" || ! -x "$WG" || ! -x "$IP" ]]; then
  echo "required networking tools are unavailable" >&2
  exit 1
fi

if ! "$IP" link show dev "$WG_IFACE" >/dev/null 2>&1; then
  exit 0
fi

modprobe ifb 2>/dev/null || true

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

desired="$SHAPE_VERSION"
if (("${#peers[@]}")); then
  desired=$(printf '%s\n%s\n' "$SHAPE_VERSION" "$(printf '%s\n' "${peers[@]}")")
fi
current="$(cat "$STATE_FILE" 2>/dev/null || true)"

if [[ "$desired" == "$current" ]] &&
   "$TC" qdisc show dev "$WG_IFACE" | grep -q 'qdisc htb 1:' &&
   "$IP" link show dev "$IFB_IFACE" >/dev/null 2>&1 &&
   "$TC" qdisc show dev "$IFB_IFACE" 2>/dev/null | grep -q 'qdisc htb 1:'; then
  echo "unchanged ${#peers[@]} peer cap(s) at $DEVICE_RATE ($SHAPE_VERSION)"
  exit 0
fi

"$IP" link add "$IFB_IFACE" type ifb 2>/dev/null || true
"$IP" link set "$IFB_IFACE" up

"$TC" qdisc del dev "$WG_IFACE" root 2>/dev/null || true
"$TC" qdisc del dev "$WG_IFACE" ingress 2>/dev/null || true
"$TC" qdisc del dev "$IFB_IFACE" root 2>/dev/null || true

"$TC" qdisc add dev "$WG_IFACE" root handle 1: htb default 999 r2q 100
"$TC" class add dev "$WG_IFACE" parent 1: classid 1:999 htb rate 1gbit ceil 1gbit burst 512kb cburst 512kb quantum 1514

"$TC" qdisc add dev "$IFB_IFACE" root handle 1: htb default 999 r2q 100
"$TC" class add dev "$IFB_IFACE" parent 1: classid 1:999 htb rate 1gbit ceil 1gbit burst 512kb cburst 512kb quantum 1514

"$TC" qdisc add dev "$WG_IFACE" handle ffff: ingress
"$TC" filter add dev "$WG_IFACE" parent ffff: protocol ip u32 match u32 0 0 action mirred egress redirect dev "$IFB_IFACE"

priority=10
for peer in "${peers[@]}"; do
  minor="$((priority + 1000))"
  "$TC" class add dev "$WG_IFACE" parent 1: classid "1:${minor}" htb rate "$DEVICE_RATE" ceil "$DEVICE_RATE" burst 1mb cburst 1mb quantum 1514
  "$TC" qdisc add dev "$WG_IFACE" parent "1:${minor}" handle "${minor}:" fq_codel
  "$TC" filter add dev "$WG_IFACE" protocol ip parent 1: prio "$priority" u32 match ip dst "$peer" flowid "1:${minor}"

  "$TC" class add dev "$IFB_IFACE" parent 1: classid "1:${minor}" htb rate "$DEVICE_RATE" ceil "$DEVICE_RATE" burst 1mb cburst 1mb quantum 1514
  "$TC" qdisc add dev "$IFB_IFACE" parent "1:${minor}" handle "${minor}:" fq_codel
  "$TC" filter add dev "$IFB_IFACE" protocol ip parent 1: prio "$priority" u32 match ip src "$peer" flowid "1:${minor}"
  priority="$((priority + 1))"
done

install -d -m 0755 "$(dirname "$STATE_FILE")"
printf '%s\n' "$desired" > "$STATE_FILE"
echo "applied ${#peers[@]} WireGuard peer bandwidth cap(s) at $DEVICE_RATE ($SHAPE_VERSION)"
