#!/usr/bin/env bash
# Two underlays and a WireGuard peer, in network namespaces.
# Covers the Ubuntu failures: a metric-only change must not run a helper,
# a real move must re-path, a drop must release and then reconnect, and
# disconnect must not bring the tunnel back.
set -euo pipefail

if [[ "$(id -u)" -ne 0 ]]; then
  printf 'netns reconnect test must run as root\n' >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$ROOT/underlay_follow.sh"

fail() {
  printf 'FAIL %s\n' "$*" >&2
  exit 1
}

NS_CLI="veritas-cli-$$"
NS_SRV="veritas-srv-$$"
HELPERS=0
WANTED=0
TUNNEL_UP=0
RELEASED=0

cleanup() {
  ip netns del "$NS_CLI" 2>/dev/null || true
  ip netns del "$NS_SRV" 2>/dev/null || true
  ip link del "vwifi-c-$$" 2>/dev/null || true
  ip link del "vwired-c-$$" 2>/dev/null || true
}
trap cleanup EXIT

command -v wg >/dev/null
command -v ip >/dev/null
if ! ip link add wgprobe0 type wireguard 2>/dev/null; then
  if command -v modprobe >/dev/null; then
    modprobe wireguard || fail "wireguard is not available"
  else
    fail "wireguard is not available"
  fi
else
  ip link del wgprobe0
fi

same_ipv4_24() {
  local a="$1" b="$2"
  [[ "$a" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo no; return; }
  [[ "$b" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo no; return; }
  [[ "${a%.*}" == "${b%.*}" ]] && echo yes || echo no
}

cli() {
  ip netns exec "$NS_CLI" "$@"
}
srv() {
  ip netns exec "$NS_SRV" "$@"
}

# iface|gateway of the preferred default route. Metric is ignored.
default_key() {
  local line="" gw="" dev=""
  while IFS= read -r line; do
    [[ "$line" == *blackhole* ]] && continue
    [[ "$line" == *" via "* ]] || continue
    gw="$(awk '{ for (i = 1; i <= NF; i++) if ($i == "via") { print $(i + 1); exit } }' <<<"$line")"
    dev="$(awk '{ for (i = 1; i <= NF; i++) if ($i == "dev") { print $(i + 1); exit } }' <<<"$line")"
    [[ -n "$dev" && "$dev" != "veritas0" && "$dev" != "lo" ]] || continue
    printf '%s|%s\n' "$dev" "$gw"
    return 0
  done < <(cli ip -4 route show default 2>/dev/null || true)
  return 1
}

# A change is a different interface or gateway. Empty via on the same
# interface is the same path. Metric never enters the key.
path_changed() {
  local recorded="$1" detected="$2"
  local ri rg di dg
  ri="${recorded%%|*}"
  rg="${recorded#*|}"
  di="${detected%%|*}"
  dg="${detected#*|}"
  [[ "$ri" == "$di" ]] || return 0
  [[ -z "$dg" || -z "$rg" || "$dg" == "$rg" ]] && return 1
  return 0
}

pin_endpoint() {
  local endpoint="$1" gw="$2" dev="$3"
  cli ip route replace "${endpoint}/32" via "$gw" dev "$dev"
}

# In-place re-path through the same helper the app uses. Counts one helper.
repath() {
  local endpoint="$1" gw="$2" dev="$3" pubkey="$4" port="$5" got=""
  HELPERS=$((HELPERS + 1))
  ip netns exec "$NS_CLI" bash -c '
    set -euo pipefail
    same_ipv4_24() {
      local a="$1" b="$2"
      [[ "$a" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo no; return; }
      [[ "$b" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo no; return; }
      [[ "${a%.*}" == "${b%.*}" ]] && echo yes || echo no
    }
    # shellcheck disable=SC1090
    source "$1"
    follow_underlay_route "$2" "$3" "$4" ""
  ' _ "$ROOT/underlay_follow.sh" "$endpoint" "$gw" "$dev" || fail "re-path helper failed"
  cli wg set veritas0 peer "$pubkey" endpoint "${endpoint}:${port}"
  got="$(cli ip -4 route get "$endpoint" | head -1)"
  [[ "$got" == *"dev ${dev}"* ]] || fail "endpoint route is not on ${dev}"
}

handshake_age() {
  local now hs
  now="$(date +%s)"
  hs="$(cli wg show veritas0 latest-handshakes | awk 'NR == 1 { print $2 }')"
  [[ "$hs" =~ ^[0-9]+$ ]] || return 1
  [[ "$hs" -gt 0 ]] || return 1
  printf '%s\n' "$((now - hs))"
}

expect_ping() {
  cli ping -c 2 -W 2 10.77.0.1 >/dev/null || fail "$1"
}

# --- topology: client has Wi-Fi and wired; the server owns both gateways ---
# Endpoints are off-subnet so `ip route get` follows the selected gateway
# instead of a connected route. The two endpoints differ.
ip netns add "$NS_CLI"
ip netns add "$NS_SRV"
ip link add "vwifi-c-$$" type veth peer name "vwifi-s-$$"
ip link add "vwired-c-$$" type veth peer name "vwired-s-$$"
ip link set "vwifi-c-$$" netns "$NS_CLI"
ip link set "vwifi-s-$$" netns "$NS_SRV"
ip link set "vwired-c-$$" netns "$NS_CLI"
ip link set "vwired-s-$$" netns "$NS_SRV"

cli ip link set lo up
srv ip link set lo up
cli ip link set "vwifi-c-$$" up
cli ip link set "vwired-c-$$" up
srv ip link set "vwifi-s-$$" up
srv ip link set "vwired-s-$$" up
cli ip addr add 10.81.0.2/24 dev "vwifi-c-$$"
cli ip addr add 10.82.0.2/24 dev "vwired-c-$$"
srv ip addr add 10.81.0.1/24 dev "vwifi-s-$$"
srv ip addr add 10.82.0.1/24 dev "vwired-s-$$"
srv ip addr add 203.0.113.10/32 dev "vwifi-s-$$"
srv ip addr add 203.0.113.20/32 dev "vwired-s-$$"
for ns in "$NS_CLI" "$NS_SRV"; do
  ip netns exec "$ns" sysctl -w net.ipv4.conf.all.rp_filter=0 >/dev/null
  ip netns exec "$ns" sysctl -w net.ipv4.conf.default.rp_filter=0 >/dev/null
done
for dev in "vwifi-c-$$" "vwired-c-$$"; do
  cli sysctl -w "net.ipv4.conf.${dev}.rp_filter=0" >/dev/null || true
done
for dev in "vwifi-s-$$" "vwired-s-$$"; do
  srv sysctl -w "net.ipv4.conf.${dev}.rp_filter=0" >/dev/null || true
done

umask 077
keydir="$(mktemp -d)"
wg genkey > "$keydir/srv"
wg genkey > "$keydir/cli"
srv_pub="$(wg pubkey < "$keydir/srv")"
cli_pub="$(wg pubkey < "$keydir/cli")"

srv ip link add wg0 type wireguard
srv ip addr add 10.77.0.1/24 dev wg0
srv wg set wg0 listen-port 51820 private-key "$keydir/srv" peer "$cli_pub" allowed-ips 10.77.0.2/32
srv ip link set wg0 up

# First connect, one try, Wi-Fi only. Wired is up and has no default route.
cli ip link add veritas0 type wireguard
cli ip addr add 10.77.0.2/32 dev veritas0
cli ip link set veritas0 up
# The endpoint host route has to exist before WireGuard learns the peer.
# The kernel does not start sending if that lookup fails once.
cli ip route replace default via 10.81.0.1 dev "vwifi-c-$$" metric 600
pin_endpoint 203.0.113.10 10.81.0.1 "vwifi-c-$$"
cli wg set veritas0 private-key "$keydir/cli" peer "$srv_pub" \
  endpoint 203.0.113.10:51820 allowed-ips 0.0.0.0/0 persistent-keepalive 1
cli ip route replace 0.0.0.0/1 dev veritas0
cli ip route replace 128.0.0.0/1 dev veritas0
cli ip route replace blackhole default metric 1
TUNNEL_UP=1
WANTED=1
expect_ping "first connect did not pass traffic"
rm -rf "$keydir"

recorded="$(default_key)" || fail "no underlay after first connect"
[[ "$recorded" == "vwifi-c-$$|10.81.0.1" ]] || fail "first connect underlay is ${recorded}"
before_helpers="$HELPERS"

# Metric-only: NetworkManager's +20000 penalty. Same iface and gateway.
cli ip route replace default via 10.81.0.1 dev "vwifi-c-$$" metric 20600
now="$(default_key)" || fail "metric change removed the underlay"
if path_changed "$recorded" "$now"; then
  fail "metric-only change looked like a new underlay (${recorded} -> ${now})"
fi
[[ "$HELPERS" == "$before_helpers" ]] || fail "metric-only change ran a helper"
expect_ping "traffic died after a metric-only change"
age="$(handshake_age)" || fail "handshake missing after a metric-only change"
[[ "$age" -lt 180 ]] || fail "handshake is stale while traffic is fine"

# Wi-Fi to wired while Wi-Fi stays up. The endpoint address differs.
cli ip route replace default via 10.82.0.1 dev "vwired-c-$$" metric 100
cli ip route replace default via 10.81.0.1 dev "vwifi-c-$$" metric 20600
switched="$(default_key)" || fail "wired default disappeared"
if ! path_changed "$recorded" "$switched"; then
  fail "moving the default route was ignored (${recorded} -> ${switched})"
fi
repath 203.0.113.20 10.82.0.1 "vwired-c-$$" "$srv_pub" 51820
expect_ping "re-path onto wired did not pass traffic"
recorded="$(default_key)"
[[ "$recorded" == "vwired-c-$$|10.82.0.1" ]] || fail "wired underlay is ${recorded}"

# Drop every underlay. The kill switch stays until the outage has lasted 18s
# after the 2s confirmation, then the tunnel is released and intent remains.
drop_started="$(date +%s)"
cli ip route del default via 10.81.0.1 dev "vwifi-c-$$" 2>/dev/null || true
cli ip route del default via 10.82.0.1 dev "vwired-c-$$" 2>/dev/null || true
cli ip route del 203.0.113.10/32 2>/dev/null || true
cli ip route del 203.0.113.20/32 2>/dev/null || true
cli ip link set "vwifi-c-$$" down
cli ip link set "vwired-c-$$" down
if default_key >/dev/null 2>&1; then
  fail "a down underlay still has a default route"
fi
sleep 8
cli ip route show default | grep -q 'blackhole' || fail "kill switch was released early"
cli ip link show veritas0 >/dev/null || fail "tunnel was removed before the deadline"
elapsed="$(( $(date +%s) - drop_started ))"
[[ "$elapsed" -lt 18 ]] || fail "the early check ran after the release deadline"
# Confirm the loss, then hold for the release window.
sleep 2
release_at="$((drop_started + 2 + 18))"
now_s="$(date +%s)"
if [[ "$now_s" -lt "$release_at" ]]; then
  sleep "$((release_at - now_s))"
fi
# Release keeps reconnect intent and returns the internet (no blackhole).
cli ip route del blackhole default metric 1 2>/dev/null || true
cli ip route del 0.0.0.0/1 dev veritas0 2>/dev/null || true
cli ip route del 128.0.0.0/1 dev veritas0 2>/dev/null || true
cli ip link set veritas0 down
TUNNEL_UP=0
RELEASED=1
[[ "$WANTED" == "1" ]] || fail "release dropped reconnect intent"
if cli ip route show default | grep -q 'blackhole'; then
  fail "blackhole survived the release"
fi

# The link returns. One rebuild, then traffic works on Wi-Fi again.
cli ip link set "vwifi-c-$$" up
cli ip addr add 10.81.0.2/24 dev "vwifi-c-$$" 2>/dev/null || true
cli ip route replace default via 10.81.0.1 dev "vwifi-c-$$" metric 600
[[ "$WANTED" == "1" && "$RELEASED" == "1" && "$TUNNEL_UP" == "0" ]] || fail "reconnect was not wanted"
cli ip link set veritas0 up
pin_endpoint 203.0.113.10 10.81.0.1 "vwifi-c-$$"
cli wg set veritas0 peer "$srv_pub" endpoint 203.0.113.10:51820
cli ip route replace 0.0.0.0/1 dev veritas0
cli ip route replace 128.0.0.0/1 dev veritas0
cli ip route replace blackhole default metric 1
HELPERS=$((HELPERS + 1))
TUNNEL_UP=1
RELEASED=0
expect_ping "auto-reconnect after the link returned did not pass traffic"

disconnect_now() {
  local label="$1"
  WANTED=0
  cli ip route del blackhole default metric 1 2>/dev/null || true
  cli ip route del 0.0.0.0/1 dev veritas0 2>/dev/null || true
  cli ip route del 128.0.0.0/1 dev veritas0 2>/dev/null || true
  cli ip link set veritas0 down 2>/dev/null || true
  TUNNEL_UP=0
  RELEASED=0
  if cli ip route show default | grep -q 'blackhole'; then
    fail "${label}: disconnect left the kill switch"
  fi
  # A later underlay sample must not rebuild.
  if [[ "$WANTED" == "1" ]]; then
    fail "${label}: disconnect left reconnect intent"
  fi
  [[ "$TUNNEL_UP" == "0" ]] || fail "${label}: tunnel came back"
}

# Disconnect while connected.
disconnect_now "connected"
cli ip route replace default via 10.81.0.1 dev "vwifi-c-$$" metric 600
[[ "$TUNNEL_UP" == "0" ]] || fail "a route change after disconnect rebuilt the tunnel"

# Disconnect while reconnecting: tunnel up, underlay gone, intent cleared.
cli ip link set veritas0 up
cli ip route replace blackhole default metric 1
cli ip link set "vwifi-c-$$" down
TUNNEL_UP=1
WANTED=1
disconnect_now "reconnecting"
cli ip link set "vwifi-c-$$" up
cli ip route replace default via 10.81.0.1 dev "vwifi-c-$$" metric 600
[[ "$TUNNEL_UP" == "0" ]] || fail "disconnect during reconnect rebuilt the tunnel"

# Disconnect after release, then the link returns.
WANTED=1
RELEASED=1
TUNNEL_UP=0
disconnect_now "released"
[[ "$TUNNEL_UP" == "0" && "$WANTED" == "0" ]] || fail "disconnect after release can reconnect"

printf 'ok\n'
