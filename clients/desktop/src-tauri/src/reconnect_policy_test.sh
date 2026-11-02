#!/usr/bin/env bash
# Decision tests for the Linux port of Android 0.2.99 reconnection.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
source "$ROOT/endpoint_choice.sh"

fail() {
  printf 'FAIL %s\n' "$*" >&2
  exit 1
}

assert_eq() {
  local got="$1" want="$2" label="$3"
  [[ "$got" == "$want" ]] || fail "$label: got [$got] want [$want]"
}

lan="192.168.0.6:51820"
wan="203.0.113.10:443"
cafe="192.168.1.57"
home="192.168.0.10"
cell="10.64.1.8"

# A Wi-Fi that merely shares the node /24 does not replace the public endpoint.
assert_eq "$(choose_underlay "$wan" "$wan" "$cafe")" "$wan public" "cafe advertised public"
assert_eq "$(choose_underlay "" "$wan" "$cafe")" "$wan public" "cafe no fresh read"
assert_eq "$(choose_underlay "$wan" "$wan" "$home")" "$wan public" "home /24 is not proof of the node LAN"

# LAN only when this read returned that private server_endpoint and the underlay is on it.
assert_eq "$(choose_underlay "$lan" "" "$home")" "$lan lan" "fresh private read on its /24"
assert_eq "$(choose_underlay "192.168.1.10:51820" "$wan" "$cafe")" "192.168.1.10:51820 lan" "fresh private on the cafe /24"
assert_eq "$(choose_underlay "$lan" "$wan" "$cell")" "$wan public" "stale private advertisement off its /24"
assert_eq "$(choose_underlay "$lan" "$wan" "192.168.1.20")" "$wan public" "different private /24"
assert_eq "$(choose_underlay "$lan" "" "$cell")" " none" "no public endpoint to fall back to"
assert_eq "$(choose_underlay "not an endpoint" "$wan" "$home")" "$wan public" "garbage advertisement is ignored"

# Stealth host follows the same read. A private stealth host is rewritten to the public host off-LAN.
assert_eq "$(choose_stealth "$lan" "" "192.168.0.6:8443" "$wan" "$cell")" "203.0.113.10:8443" "stealth leaves the node LAN"
assert_eq "$(choose_stealth "$wan" "" "192.168.1.10:8443" "$wan" "$cafe")" "203.0.113.10:8443" "stealth ignores a shared /24"
assert_eq "$(choose_stealth "192.168.1.10:51820" "" "203.0.113.10:8443" "$wan" "$cafe")" "192.168.1.10:8443" "stealth uses the fresh LAN host"
assert_eq "$(choose_stealth "$lan" "192.168.0.6:8443" "203.0.113.10:8443" "$wan" "$home")" "192.168.0.6:8443" "fresh stealth endpoint on the node LAN"

# Dial the resolved address and keep the name for SNI.
prep="$(wstunnel_prepare_line "vpn.example.com:443" "203.0.113.10")"
assert_eq "$(printf '%s\n' "$prep" | sed -n '1p')" "url wss://203.0.113.10:443" "wstunnel url is the resolved address"
assert_eq "$(printf '%s\n' "$prep" | sed -n '2p')" "sni vpn.example.com" "wstunnel sni keeps the name"
same="$(wstunnel_prepare_line "203.0.113.10:443" "203.0.113.10")"
assert_eq "$(printf '%s\n' "$same" | sed -n '1p')" "url wss://203.0.113.10:443" "already an address"
assert_eq "$(printf '%s\n' "$same" | sed -n '2p')" "sni " "no sni override for an address"
fallback="$(wstunnel_prepare_line "vpn.example.com:443" "")"
assert_eq "$(printf '%s\n' "$fallback" | sed -n '1p')" "url wss://vpn.example.com:443" "unresolved name is dialed as a name"

decide() {
  stealth_decide "$@"
}

# One network change dials once. Later samples on that network do not dial again.
settled="$(decide wifi wifi 1 0 2000 1500 $((1000 - 11)) 1000 "" 0 0 0 0)"
assert_eq "${settled%%$'\t'*}" "HEALTHY" "settled tunnel"
switched="$(decide cell wifi 1 0 2000 1500 $((1001 - 11)) 1001 wifi 1000 2000 0 1)"
assert_eq "${switched%%$'\t'*}" "DIAL" "new gateway dials once"
assert_eq "$(printf '%s' "$switched" | awk -F '\t' '{ print $5 }')" "1" "one dial"
burst="$(decide cell wifi 1 1 2000 1500 0 1002 cell 1001 2000 1 1)"
assert_eq "${burst%%$'\t'*}" "WAIT" "repeat callback during the cooldown waits"
healthy="$(decide cell cell 1 0 16000 13000 $((1003 - 11)) 1003 cell 1001 2000 1 1)"
assert_eq "${healthy%%$'\t'*}" "HEALTHY" "bytes or a fresh handshake stay up"
assert_eq "$(printf '%s' "$healthy" | awk -F '\t' '{ print $5 }')" "1" "healthy sample does not dial"

again="$(decide wifi cell 1 0 20000 18000 $((1040 - 11)) 1040 cell 1001 16000 1 1)"
assert_eq "${again%%$'\t'*}" "DIAL" "a second network dials once more"
assert_eq "$(printf '%s' "$again" | awk -F '\t' '{ print $5 }')" "1" "one dial on the new network"

# Transmit growth alone is not a live tunnel.
tx_only="$(decide cell cell 1 0 1000 80000 0 $((1000 + 10)) cell 1000 1000 1 1)"
assert_eq "${tx_only%%$'\t'*}" "WAIT" "tx-only during the cooldown waits"
tx_later="$(decide cell cell 1 0 1000 80000 0 $((1000 + 31)) cell 1000 1000 1 1)"
assert_eq "${tx_later%%$'\t'*}" "DIAL" "tx-only after the cooldown can redial"
assert_eq "$(printf '%s' "$tx_later" | awk -F '\t' '{ print $5 }')" "2" "tx-only retry is the extra dial"
tx_cap="$(decide cell cell 1 0 1000 80000 0 $((1000 + 80)) cell $((1000 + 31)) 1000 2 1)"
assert_eq "${tx_cap%%$'\t'*}" "WAIT" "tx-only stops after the extra retry"

# Handshake under 180s is healthy even when the byte counter did not move.
fresh_hs="$(decide cell cell 1 0 1000 1000 $((5000 - 11)) 5000 cell 1000 1000 1 1)"
assert_eq "${fresh_hs%%$'\t'*}" "HEALTHY" "11s handshake is healthy"
stale_hs="$(decide cell cell 1 0 1000 1000 $((5000 - 181)) 5000 cell 1000 1000 1 1)"
assert_eq "${stale_hs%%$'\t'*}" "DIAL" "181s handshake with no new bytes can redial"
stale_cap="$(decide cell cell 1 0 1000 1000 $((5100 - 181)) 5100 cell 5031 1000 2 1)"
assert_eq "${stale_cap%%$'\t'*}" "WAIT" "stale handshake does not dial a third time"

# Same network, process still the one we dialed, inside 30s: no second restart.
early="$(decide cell cell 0 0 100 100 0 $((1000 + 10)) cell 1000 100 1 1)"
assert_eq "${early%%$'\t'*}" "WAIT" "dead tunnel still waits out 30s"
later="$(decide cell cell 0 0 100 100 0 $((1000 + 31)) cell 1000 100 1 1)"
assert_eq "${later%%$'\t'*}" "DIAL" "dead tunnel redials after the cooldown"
assert_eq "$(printf '%s' "$later" | awk -F '\t' '{ print $5 }')" "2" "dead tunnel retry is the extra dial"
capped="$(decide cell cell 0 0 100 100 0 $((1000 + 70)) cell $((1000 + 31)) 100 2 1)"
assert_eq "${capped%%$'\t'*}" "WAIT" "no third dial after the extra retry"

lib="$ROOT/lib.rs"
if grep -q 'CHOOSE_IP="$LAN_HOST"' "$lib"; then
  fail "path-adapt still guesses the LAN endpoint from a shared /24"
fi
if ! grep -q 'apply_endpoint_choice' "$lib"; then
  fail "path-adapt does not apply the fresh peer-read choice"
fi
if ! grep -q 'maybe_restart_stealth' "$lib"; then
  fail "path-adapt does not restart stealth from the health gate"
fi
if grep -q 'echo "ok endpoint=' "$lib"; then
  fail "path-adapt logs the endpoint address"
fi
if ! grep -q 'stealth-roam' "$lib"; then
  fail "connect does not start the stealth restart cooldown"
fi

# shellcheck disable=SC1091
source "$ROOT/stealth_apply.sh"
stealth_paths_ok /usr/lib/veritasvpn/wstunnel /vpn && true || fail "bundled wstunnel path rejected"
stealth_paths_ok "wstunnel" /vpn && fail "relative wstunnel path accepted"
stealth_paths_ok '/tmp/wstunnel;id' /vpn && fail "shell metacharacter in binary accepted"
stealth_paths_ok /tmp/../wstunnel /vpn && fail "binary traversal accepted"
stealth_paths_ok /usr/bin/wstunnel /../tmp && fail "prefix traversal accepted"

# A failed fresh read never installs a stored private address.
STEALTH_REMOTE=""
FRESH_EP=""
FRESH_STEALTH=""
ENDPOINT_WAN="203.0.113.10:51820"
ENDPOINT="192.168.0.6:51820"
NEW_SRC="10.1.1.8"
apply_endpoint_choice
assert_eq "$CHOOSE_IP" "203.0.113.10" "failed fresh read uses the public endpoint"
assert_eq "$CHOOSE_EP" "203.0.113.10:51820" "public endpoint replaces a stored private one"

STEALTH_REMOTE="192.168.0.6:8443"
FRESH_EP=""
FRESH_STEALTH=""
ENDPOINT_WAN=""
ENDPOINT="192.168.0.6:51820"
NEW_SRC="10.1.1.8"
apply_endpoint_choice
assert_eq "$STEALTH_DIAL" "" "no private stealth fallback"
assert_eq "$CHOOSE_IP" "" "no private address installed when the fresh read fails"

ENDPOINT_WAN="203.0.113.10:51820"
apply_endpoint_choice
assert_eq "$STEALTH_DIAL" "203.0.113.10:8443" "stealth falls forward to the public host"
assert_eq "$CHOOSE_IP" "203.0.113.10" "stealth pin is the public address"

STEALTH_REMOTE=""
FRESH_EP="192.168.0.6:51820"
ENDPOINT_WAN="203.0.113.10:51820"
NEW_SRC="192.168.0.10"
apply_endpoint_choice
assert_eq "$CHOOSE_IP" "192.168.0.6" "fresh private read on its /24 is kept"

# shellcheck disable=SC1091
source "$ROOT/fresh_peer.sh"
assert_eq "$(fresh_due "" 0 0 "gw|eth0" 1000)" "read 0" "new network reads immediately"
assert_eq "$(fresh_due "gw|eth0" 1000 0 "gw|eth0" 1029)" "skip" "same network waits 30s"
assert_eq "$(fresh_due "gw|eth0" 1000 0 "gw|eth0" 1030)" "read 1" "30s read stores step 1"
assert_eq "$(fresh_due "gw|eth0" 1000 1 "gw|eth0" 1059)" "skip" "step 1 waits 60s"
assert_eq "$(fresh_due "gw|eth0" 1000 1 "gw|eth0" 1060)" "read 2" "60s read stores step 2"
assert_eq "$(fresh_due "gw|eth0" 1000 2 "gw|eth0" 1119)" "skip" "step 2 waits 120s"
assert_eq "$(fresh_due "gw|eth0" 1000 2 "gw|eth0" 1120)" "read 3" "120s read stores step 3"
assert_eq "$(fresh_due "gw|eth0" 1000 3 "gw|eth0" 1299)" "skip" "step 3 waits 5 min"
assert_eq "$(fresh_due "gw|eth0" 1000 3 "gw|eth0" 1300)" "read 3" "later reads stay at 5 min"
assert_eq "$(fresh_due "gw|eth0" 1000 3 "other|eth1" 1001)" "read 0" "network change reads immediately"

home_key="$(fresh_network_key "192.168.1.1" "wlan0" "192.168.1.50" "aa:bb:cc:dd:ee:ff")"
other_src="$(fresh_network_key "192.168.1.1" "wlan0" "10.0.0.8" "aa:bb:cc:dd:ee:ff")"
other_mac="$(fresh_network_key "192.168.1.1" "wlan0" "192.168.1.50" "11:22:33:44:55:66")"
assert_eq "$(fresh_due "$home_key" 1000 0 "$other_src" 1001)" "read 0" "same gateway and iface with a different src re-reads"
assert_eq "$(fresh_due "$home_key" 1000 0 "$other_mac" 1001)" "read 0" "same gateway and iface with a different mac re-reads"
assert_eq "$(fresh_due "$home_key" 1000 0 "$home_key" 1010)" "skip" "same src and mac waits"

private_skip="$(fresh_cached_lines "192.168.0.6:51820" "192.168.0.6:8443")"
assert_eq "$private_skip" "0" "skip drops a private endpoint"
if printf '%s\n' "$private_skip" | grep -q '192\.168'; then
  fail "skip yielded a private endpoint"
fi
public_skip="$(fresh_cached_lines "203.0.113.10:51820" "203.0.113.10:8443")"
assert_eq "$(printf '%s\n' "$public_skip" | sed -n '1p')" "cached" "backed-off read is not a fresh 200"
if printf '%s\n' "$public_skip" | grep -qx '200'; then
  fail "cached read uses the fresh code"
fi

fresh_tmp="$(mktemp -d)"
STATE_DIR="$fresh_tmp"
fresh_now="$(date +%s)"
save_fresh_state "$home_key" "$fresh_now" 0 "192.168.0.6:51820" "192.168.0.6:8443"
skipped="$(maybe_fresh_lines "192.168.1.1" "wlan0" "192.168.1.50" "aa:bb:cc:dd:ee:ff")"
rm -rf "$fresh_tmp"
unset STATE_DIR
if printf '%s\n' "$skipped" | grep -q '192\.168\.0\.6'; then
  fail "a skipped read returned the cached LAN endpoint"
fi
assert_eq "$(printf '%s\n' "$skipped" | sed -n '1p')" "0" "a skipped private cache is not a fresh read"

# shellcheck disable=SC1091
source "$ROOT/recover_swap.sh"
routes="$(recovery_route_lines "10.7.0.2/32" "0.0.0.0/0,10.8.0.0/24,::/0")"
assert_eq "$(printf '%s\n' "$routes" | sed -n '1p')" "0.0.0.0/1" "full tunnel first half"
assert_eq "$(printf '%s\n' "$routes" | sed -n '2p')" "128.0.0.0/1" "full tunnel second half"
assert_eq "$(printf '%s\n' "$routes" | sed -n '3p')" "10.8.0.0/24" "other allowed range"
assert_eq "$(printf '%s\n' "$routes" | sed -n '4p')" "::/1" "ipv6 first half"
assert_eq "$(printf '%s\n' "$routes" | sed -n '5p')" "8000::/1" "ipv6 second half"
if printf '%s\n' "$routes" | grep -q '^0.0.0.0/0$'; then
  fail "recovery installs a literal default route"
fi
empty_routes="$(recovery_route_lines "10.7.0.2/32" "")"
assert_eq "$empty_routes" "" "empty allowed list adds no route"

bash -n "$ROOT/endpoint_choice.sh"
bash -n "$ROOT/fresh_peer.sh"
bash -n "$ROOT/stealth_apply.sh"
bash -n "$ROOT/recover_swap.sh"

# The actuator must not tear the kill switch down while restarting or recovering.
if grep -E -q 'nft delete table|cleanup_killswitch|ip route del blackhole' "$ROOT/stealth_apply.sh"; then
  fail "stealth restart removes the kill switch"
fi
if grep -E -q 'nft delete table|cleanup_killswitch|ip route del blackhole' "$ROOT/recover_swap.sh"; then
  fail "reconnect removes the kill switch"
fi
if grep -E -q 'echo "\$token"|printf '"'"'%s\\n'"'"' "\$token"' "$ROOT/fresh_peer.sh"; then
  fail "peer read prints the access token"
fi
if grep 'curl ' "$ROOT/fresh_peer.sh" | grep -q 'peers/'; then
  fail "peer id is in curl argv"
fi
if ! grep -q 'url = "https://api.veritasvpn.cloud/api/v1/wg/peers/%s"' "$ROOT/fresh_peer.sh"; then
  fail "peer url is not in the curl config"
fi
if grep -q '/tmp/veritas-wstunnel.log' "$lib" "$ROOT/stealth_apply.sh"; then
  fail "wstunnel log is copied to the UI or /tmp"
fi
if grep -q 'check_current_peer' "$lib"; then
  fail "outside-tunnel peer poll is still registered"
fi
if grep -q 'LAN_HOST=' "$lib" && grep -q 'LAN_HOST=' <(sed -n '/<<'"'"'SOFT_PATH'"'"'/,/^SOFT_PATH$/p' "$lib"); then
  fail "path-adapt still carries the dead LAN host"
fi
if grep -q '10.0.0.0/24' "$ROOT/recover_swap.sh"; then
  fail "recovery hardcodes the tunnel subnet"
fi
if ! grep -q 'systemd-run' "$ROOT/recover_swap.sh"; then
  fail "recovery exception has no systemd expiry"
fi
if ! grep -q 'expire-after' "$ROOT/recover_swap.sh"; then
  fail "recovery exception has no sleep fallback"
fi
if ! grep -q 'maybe_fresh_lines' "$lib"; then
  fail "path-adapt does not gate the fresh read"
fi
app="$ROOT/../../src/App.tsx"
if grep -q 'last_handshake_sec > latchedAtSecRef' "$app"; then
  fail "handshake clears the peer-gone latch"
fi
if ! grep -q 'RECOVERY_REQUEST_MS = 10_000' "$app"; then
  fail "recovery requests have no 10s deadline"
fi
if ! grep -q 'rm -f "$RUNTIME_DIR/fresh-read"' "$lib"; then
  fail "connect does not clear the fresh-read cache"
fi
if ! grep -q 'STATE_DIR/fresh-read' "$lib"; then
  fail "teardown does not clear the fresh-read cache"
fi
if ! grep -q 'dirname "$IFACE_FILE")/fresh-read' "$lib"; then
  fail "disconnect does not clear the fresh-read cache"
fi
if ! grep -q 'clear_fresh_state' "$ROOT/recover_swap.sh"; then
  fail "recovery does not clear the fresh-read cache"
fi
if ! grep -q '_fresh_code" == "200"' "$lib"; then
  fail "path-adapt treats a cached read as fresh"
fi
if ! grep -q 'recoveryRetryAction' "$app"; then
  fail "recovery retries are not capped"
fi
if ! grep -q 'start_handshake_publisher' "$lib"; then
  fail "connect does not publish the handshake"
fi
if ! grep -q 'stop_handshake_publisher' "$lib"; then
  fail "teardown does not stop the handshake publisher"
fi
if ! grep -q 'start_handshake_publisher "$STATE_DIR"' "$ROOT/recover_swap.sh"; then
  fail "recovery does not publish the handshake"
fi
if grep -q 'NOPASSWD:.*handshake\.sh' "$lib"; then
  fail "handshake publisher is passwordless sudo"
fi
if grep -q '/var/run/wireguard/${{IFACE}}.sock' "$lib"; then
  fail "UAPI socket is delegated to the desktop user"
fi
python3 - "$lib" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text()
start = text.find("fn wireguard_stats_linux()")
end = text.find("async fn disconnect_wireguard")
if start < 0 or end < 0:
    sys.exit("wireguard_stats_linux missing")
body = text[start:end]
if 'Command::new("wg")' in body:
    sys.exit("unprivileged stats still reads wg dump")
if "read_published_handshake" not in body:
    sys.exit("stats does not read the published handshake")
PY

# shellcheck disable=SC1091
source "$ROOT/handshake_publish.sh"
priv=$'PRIVKEY_DO_NOT_LEAK\tpub\t51820\toff\npeerpub\t\t203.0.113.10:51820\t0.0.0.0/0\t1700000000\t10\t20\t25\npeer2\t(none)\t203.0.113.11:51820\t0.0.0.0/0\t1700000099\t1\t2\t25\n'
err="$(mktemp)"
got="$(printf '%s' "$priv" | handshake_from_dump 2>"$err")"
assert_eq "$got" "1700000099" "newest handshake wins and the private key line is skipped"
if [[ -s "$err" ]]; then
  fail "handshake parser wrote stderr"
fi
if [[ "$got" == *PRIVKEY* || "$got" == *peerpub* ]]; then
  fail "handshake parser echoed the dump"
fi
rm -f "$err"
keyline=$'111\t222\t333\t444\t9999999999\npeer\t(none)\tend\tips\t5\t1\t2\t25\n'
assert_eq "$(printf '%s' "$keyline" | handshake_from_dump)" "5" "interface line is not a handshake"
assert_eq "$(printf '%s' $'only-the-interface-line\n' | handshake_from_dump)" "0" "no peer means no handshake"
bash -n "$ROOT/handshake_publish.sh"

watch="$(mktemp)"
{
  printf '%s\n' '#!/bin/bash' 'set -uo pipefail'
  declare -f handshake_from_dump
  awk '
    /<<'"'"'HANDSHAKE_BODY'"'"'/ { grab=1; next }
    grab && $0 == "HANDSHAKE_BODY" { exit }
    grab { print }
  ' "$ROOT/handshake_publish.sh"
} > "$watch"
bash -n "$watch"
if ! grep -q 'exec 9>&-; wg show veritas0 dump 2>/dev/null | handshake_from_dump' "$ROOT/handshake_publish.sh"; then
  fail "watcher pipe does not close the lock fd"
fi
if ! grep -q 'sleep 2 9>&-' "$watch"; then
  fail "watcher sleep inherits the lock fd"
fi
if grep -q 'echo ' "$watch"; then
  fail "watcher echoes output"
fi
if grep -q 'chmod 600 "$dir/handshake"' "$ROOT/handshake_publish.sh"; then
  fail "chmod follows the handshake path"
fi
if grep -q 'chown "$uid:"' "$ROOT/handshake_publish.sh"; then
  fail "numeric chown spec is rejected by coreutils"
fi
if ! grep -q 'logger -t veritasvpn-handshake' "$ROOT/handshake_publish.sh"; then
  fail "handshake write failure is silent"
fi
if grep -q 'logger -t veritasvpn-handshake -- "$hs"' "$ROOT/handshake_publish.sh"; then
  fail "handshake log includes the value"
fi

# The installed loop body, with the lock held, writes one integer from a kernel dump.
hsdir="$(mktemp -d)"
wgdir="$(mktemp -d)"
printf 'veritas0\n' > "$hsdir/iface"
cat > "$wgdir/wg" << 'EOF'
#!/bin/bash
if [[ "$1 $2 $3" == "show veritas0 dump" ]]; then
  printf 'PRIVKEY_DO_NOT_LEAK\tpub\t51820\toff\n'
  printf 'peerpub\t(none)\t203.0.113.10:51820\t0.0.0.0/0\t1700000099\t10\t20\t25\n'
  exit 0
fi
exit 1
EOF
chmod +x "$wgdir/wg"
bash -c '
set -uo pipefail
source "$1"
exec 9>"$2/handshake.lock"
flock -n 9 || exit 1
PATH="$3:$PATH" publish_watcher_once "$2" "$4"
' _ "$ROOT/handshake_publish.sh" "$hsdir" "$wgdir" "$(id -u)" || fail "watcher iteration failed"
assert_eq "$(cat "$hsdir/handshake" 2>/dev/null || true)" "1700000099" "watcher wrote the kernel handshake"
assert_eq "$(stat -c %a "$hsdir/handshake")" "600" "handshake mode"
assert_eq "$(stat -c %u "$hsdir/handshake")" "$(id -u)" "handshake owner"
if grep -q PRIVKEY "$hsdir/handshake"; then
  fail "handshake file contains the private key"
fi
rm -rf "$hsdir" "$wgdir"

if ! grep -q 'kill -- "-$pid"' "$ROOT/handshake_publish.sh"; then
  fail "stop does not signal the watcher process group"
fi
rm -f "$watch"

# A child must not keep flock after the watcher is killed.
lock="$(mktemp)"
pidf="$(mktemp)"
bash -c 'exec 9>"$1"; flock -n 9 || exit 1; echo $$ > "$2"; hs="$(exec 9>&-; sleep 25 9>&- | true 9>&-)"; sleep 25 9>&-' _ "$lock" "$pidf" &
for _ in $(seq 1 40); do
  [[ -s "$pidf" ]] && break
  sleep 0.05
done
parent="$(cat "$pidf" 2>/dev/null || true)"
[[ "$parent" =~ ^[0-9]+$ ]] || fail "lock test did not start"
kill "$parent" 2>/dev/null || true
sleep 0.15
if ! flock -n "$lock" true; then
  kill "$parent" 2>/dev/null || true
  pkill -P "$parent" 2>/dev/null || true
  ps -eo pid,args | awk '$2 == "sleep" && $3 == "25" { print $1 }' | while read -r p; do kill "$p" 2>/dev/null || true; done
  rm -f "$lock" "$pidf"
  fail "watcher child kept the handshake lock"
fi
ps -eo pid,args | awk '$2 == "sleep" && $3 == "25" { print $1 }' | while read -r p; do kill "$p" 2>/dev/null || true; done
rm -f "$lock" "$pidf"

if command -v setsid >/dev/null 2>&1; then
  pidf="$(mktemp)"
  setsid bash -c 'sleep 27 & echo $$ > "$1"; wait' _ "$pidf" >/dev/null 2>&1 &
  for _ in $(seq 1 40); do
    [[ -s "$pidf" ]] && break
    sleep 0.05
  done
  parent="$(cat "$pidf" 2>/dev/null || true)"
  pgid="$(ps -o pgid= -p "$parent" 2>/dev/null | tr -d ' ' || true)"
  [[ "$pgid" == "$parent" ]] || fail "setsid watcher is not its process group"
  kill -- "-$parent" 2>/dev/null || true
  sleep 0.15
  if kill -0 "$parent" 2>/dev/null; then
    kill -9 -- "-$parent" 2>/dev/null || true
    rm -f "$pidf"
    fail "process group kill left the watcher"
  fi
  if ps -eo pid,args | awk '$2 == "sleep" && $3 == "27" { found=1 } END { exit !found }'; then
    ps -eo pid,args | awk '$2 == "sleep" && $3 == "27" { print $1 }' | while read -r p; do kill "$p" 2>/dev/null || true; done
    rm -f "$pidf"
    fail "process group kill left a watcher child"
  fi
  rm -f "$pidf"
fi

cleanup="$ROOT/../linux/killswitch-cleanup.sh"
conf="$ROOT/../tauri.conf.json"
[[ -f "$cleanup" ]] || fail "killswitch cleanup script missing"
bash -n "$cleanup" || fail "killswitch cleanup syntax"
grep -q 'nft delete table inet veritasvpn_killswitch' "$cleanup" || fail "cleanup does not delete the veritas nft table"
grep -q 'ip route del blackhole default metric 1' "$cleanup" || fail "cleanup does not delete the v4 blackhole"
grep -q 'ip -6 route del blackhole default metric 1' "$cleanup" || fail "cleanup does not delete the v6 blackhole"
grep -q 'ip link delete veritas0' "$cleanup" || fail "cleanup does not delete veritas0"
grep -q 'restore_veritas_dns' "$cleanup" || fail "cleanup does not restore DNS"
grep -q 'resolvectl default-route' "$cleanup" || fail "cleanup does not restore the default DNS route"
grep -q 'timeout' "$cleanup" || fail "cleanup resolver commands have no timeout"
grep -q 'tailscale0' "$cleanup" || fail "cleanup does not exclude tailscale"
if grep -q 'physical_links' "$cleanup" || grep -q 'physical_dns_stuck' "$ROOT/lib.rs"; then
  fail "DNS cleanup scans physical NICs the app did not record"
fi
if grep -q 'resolvectl revert "$' "$cleanup" || grep -q 'resolvectl revert "$gw_if"' "$ROOT/lib.rs" || grep -q 'resolvectl revert "$old_gif"' "$ROOT/recover_swap.sh"; then
  fail "DNS cleanup reverts a physical link"
fi
if ! grep -q 'apply_tunnel_dns' "$ROOT/lib.rs"; then
  fail "connect does not scope DNS to the tunnel"
fi
if grep -q 'resolvectl dns "$GW_IF"' "$ROOT/lib.rs" || grep -q 'resolvectl dns "$NEW_GW_IF"' "$ROOT/lib.rs" || grep -q 'resolvectl dns "$gif"' "$ROOT/recover_swap.sh"; then
  fail "DNS is still pinned to the physical link"
fi
if grep -q 'table 52' "$cleanup" || grep -q '5210' "$cleanup" || grep -q 'ip rule' "$cleanup" || grep -q 'nft flush ruleset' "$cleanup"; then
  fail "cleanup touches another vendor"
fi
grep -q 'install|upgrade|remove' "$cleanup" || fail "desktop stop is not limited to maintainer scripts"
bash "$cleanup" || fail "cleanup script failed when nothing was installed"
grep -q '"preInstallScript": "./linux/killswitch-cleanup.sh"' "$conf" || fail "deb preinst is not the cleanup script"
grep -q '"preRemoveScript": "./linux/killswitch-cleanup.sh"' "$conf" || fail "deb prerm is not the cleanup script"
grep -q 'ip route del blackhole default metric 1' "$ROOT/lib.rs" || fail "connect-time cleanup dropped the v4 blackhole"
grep -q 'ip -6 route del blackhole default metric 1' "$ROOT/lib.rs" || fail "connect-time cleanup dropped the v6 blackhole"
if grep -n 'NOPASSWD' "$ROOT/lib.rs" | grep -E 'killswitch-cleanup|cleanup-killswitch'; then
  fail "cleanup helper is passwordless"
fi

python3 - "$app" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text()
start = text.find("const pollPeers = async () => {")
if start < 0:
    sys.exit("pollPeers missing")
i = text.find("{", start)
depth = 0
body = ""
for j in range(i, len(text)):
    if text[j] == "{":
        depth += 1
    elif text[j] == "}":
        depth -= 1
        if depth == 0:
            body = text[i:j + 1]
            break
else:
    sys.exit("pollPeers unterminated")
banned = ["check_current_peer", "peer-check", "nft", "ip route", "read_fresh", "sudo"]
for word in banned:
    if word in body:
        sys.exit("5s poll touches " + word)
PY

dns_sandbox="$(mktemp -d)"
mkdir -p "$dns_sandbox/bin"
cat > "$dns_sandbox/bin/resolvectl" << 'EOF'
#!/bin/bash
printf '%s\n' "$*" >> "$DNS_LOG"
if [[ "$1" == status ]]; then
  if grep -q 'default-route eno1 true' "$DNS_LOG"; then
    printf '%s\n' 'Protocols: +DefaultRoute'
  else
    printf '%s\n' 'Protocols: -DefaultRoute'
  fi
fi
exit 0
EOF
cat > "$dns_sandbox/bin/nmcli" << 'EOF'
#!/bin/bash
printf 'nmcli %s\n' "$*" >> "$DNS_LOG"
exit 0
EOF
cat > "$dns_sandbox/bin/systemctl" << 'EOF'
#!/bin/bash
printf 'systemctl %s\n' "$*" >> "$DNS_LOG"
exit 0
EOF
cat > "$dns_sandbox/bin/timeout" << 'EOF'
#!/bin/bash
shift
exec "$@"
EOF
chmod +x "$dns_sandbox/bin/"*
printf 'eno1\n' > "$dns_sandbox/links"
printf 'nameserver 192.0.2.1\n' > "$dns_sandbox/backup"
printf 'gw_if=eno1\n' > "$dns_sandbox/meta"
: > "$dns_sandbox/log"
PATH="$dns_sandbox/bin:$PATH" DNS_LOG="$dns_sandbox/log" bash -c '
  set -uo pipefail
  source "$1"
  restore_veritas_dns "$2" "$3" "$4" ""
' _ "$ROOT/dns_restore.sh" "$dns_sandbox/links" "$dns_sandbox/backup" "$dns_sandbox/meta" || fail "recorded DNS restore failed"
dns_log="$(cat "$dns_sandbox/log")"
printf '%s\n' "$dns_log" | grep -q 'default-route eno1 true' || fail "recorded link was not restored"
printf '%s\n' "$dns_log" | grep -q 'revert veritas0' || fail "tunnel DNS was not cleared"
if printf '%s\n' "$dns_log" | grep -Eq 'eno2|revert eno1'; then
  fail "DNS restore touched a link it did not record"
fi
[[ ! -e "$dns_sandbox/meta" && ! -e "$dns_sandbox/backup" && ! -e "$dns_sandbox/links" ]] || fail "DNS record kept after a successful restore"
rm -rf "$dns_sandbox"

# The underlay record is written before its default DNS route is turned off.
order_sandbox="$(mktemp -d)"
mkdir -p "$order_sandbox/bin" "$order_sandbox/state"
cat > "$order_sandbox/bin/resolvectl" << 'EOF'
#!/bin/bash
printf '%s\n' "$*" >> "$DNS_LOG"
if [[ "$1" == default-route && "$2" == eno1 && "$3" == false ]]; then
  if grep -qx 'eno1' "$LINKS_FILE" && grep -qx 'gw_if=eno1' "$META_FILE"; then
    printf '%s\n' ORDER_OK >> "$DNS_LOG"
  else
    printf '%s\n' ORDER_FAIL >> "$DNS_LOG"
  fi
fi
exit 0
EOF
cat > "$order_sandbox/bin/timeout" << 'EOF'
#!/bin/bash
shift
exec "$@"
EOF
chmod +x "$order_sandbox/bin/"*
: > "$order_sandbox/log"
PATH="$order_sandbox/bin:$PATH" DNS_LOG="$order_sandbox/log" \
  LINKS_FILE="$order_sandbox/state/iface.meta.links" \
  META_FILE="$order_sandbox/state/iface.meta" \
  bash -c '
    set -uo pipefail
    source "$1"
    apply_tunnel_dns 10.0.0.1 eno1 "$2"
  ' _ "$ROOT/dns_restore.sh" "$order_sandbox/state/iface.meta.links" || fail "apply_tunnel_dns failed"
order_log="$(cat "$order_sandbox/log")"
printf '%s\n' "$order_log" | grep -qx ORDER_OK || fail "underlay was changed before it was recorded"
if printf '%s\n' "$order_log" | grep -qx ORDER_FAIL; then
  fail "underlay was changed before it was recorded"
fi
grep -qx 'eno1' "$order_sandbox/state/iface.meta.links" || fail "links file missing the underlay"
grep -qx 'gw_if=eno1' "$order_sandbox/state/iface.meta" || fail "iface.meta missing gw_if"
rm -rf "$order_sandbox"

# Underlay switch: the endpoint host route follows the new default interface.
# shellcheck disable=SC1091
source "$ROOT/endpoint_choice.sh"
# shellcheck disable=SC1091
source "$ROOT/underlay_follow.sh"
route_log="$(mktemp)"
ip() {
  printf '%s\n' "$*" >> "$route_log"
  if [[ "${1:-}" == "-4" && "${2:-}" == "route" && "${3:-}" == "get" ]]; then
    local last="" dev=""
    last="$(grep 'route replace' "$route_log" | tail -1 || true)"
    dev="$(printf '%s\n' "$last" | sed -n 's/.* dev \([^ ]*\).*/\1/p')"
    if [[ -n "$dev" ]]; then
      printf '%s via 198.51.100.1 dev %s src 198.51.100.20\n' "${4:-}" "$dev"
    else
      printf '%s dev veritas0\n' "${4:-}"
    fi
  fi
  return 0
}
follow_underlay_route 203.0.113.10 198.51.100.1 eno1 198.51.100.20 || fail "wired underlay was not ready"
grep -q 'route replace 203.0.113.10/32 via 198.51.100.1 dev eno1' "$route_log" || fail "endpoint did not move to the wired underlay"
if grep -q 'route del ' "$route_log"; then
  fail "endpoint route was deleted before the replacement was verified"
fi
if grep -q 'wlx90de802153e1' "$route_log"; then
  fail "endpoint route stayed on the old wifi interface"
fi
: > "$route_log"
follow_underlay_route 203.0.113.10 192.0.2.1 wlx90de802153e1 192.0.2.50 || fail "wifi underlay was not ready"
grep -q 'route replace 203.0.113.10/32 via 192.0.2.1 dev wlx90de802153e1' "$route_log" || fail "endpoint did not move to the wifi underlay"
if follow_underlay_route 203.0.113.10 "" eno1 198.51.100.20; then
  fail "a missing gateway was treated as ready"
fi

# A failed replace must not look like success, and must not report the route gone.
: > "$route_log"
ip() {
  printf '%s\n' "$*" >> "$route_log"
  if [[ "${1:-}" == "route" && "${2:-}" == "replace" ]]; then
    return 1
  fi
  if [[ "${1:-}" == "-4" && "${2:-}" == "route" && "${3:-}" == "get" ]]; then
    printf '%s dev veritas0 src 10.0.0.2\n' "${4:-}"
  fi
  return 0
}
if follow_underlay_route 203.0.113.10 198.51.100.1 eno1 198.51.100.20; then
  fail "a failed route replace was reported as a successful re-path"
fi

# Wi-Fi goes down while Ethernet is already up with its own default route.
# The endpoint host route must move to Ethernet, not stay on the dead NIC.
sys_root="$(mktemp -d)"
mkdir -p "$sys_root/class/net/wlx90de802153e1" "$sys_root/class/net/eno1"
printf 'down\n' > "$sys_root/class/net/wlx90de802153e1/operstate"
printf 'up\n' > "$sys_root/class/net/eno1/operstate"
: > "$route_log"
ip() {
  if [[ "${1:-}" == "-4" && "${2:-}" == "route" && "${3:-}" == "show" ]]; then
    printf '%s\n' 'blackhole default metric 1'
    printf '%s\n' 'default via 192.0.2.1 dev wlx90de802153e1 metric 600'
    printf '%s\n' 'default via 198.51.100.1 dev eno1 proto dhcp metric 20100'
    return 0
  fi
  printf '%s\n' "$*" >> "$route_log"
  if [[ "${1:-}" == "-4" && "${2:-}" == "route" && "${3:-}" == "get" ]]; then
    printf '%s\n' '203.0.113.10 via 198.51.100.1 dev eno1 src 198.51.100.20'
  fi
  return 0
}
export VERITAS_SYS_ROOT="$sys_root"
picked="$(pick_underlay veritas0)" || fail "live ethernet underlay was not selected"
[[ "$picked" == "198.51.100.1 eno1" ]] || fail "handover picked '$picked' instead of the up ethernet default"
: > "$route_log"
follow_underlay_route 203.0.113.10 198.51.100.1 eno1 198.51.100.20 || fail "handover did not install the ethernet host route"
grep -q 'route replace 203.0.113.10/32 via 198.51.100.1 dev eno1' "$route_log" || fail "endpoint host route was not pointed at eno1"
if grep -q 'wlx90de802153e1' "$route_log"; then
  fail "handover left the endpoint on the down wifi interface"
fi
rm -rf "$sys_root"

# UFW must accept decrypted packets on veritas0, and only that interface.
# rp_filter is loosened on veritas0 and the new underlay, not globally.
ufw_log="$(mktemp)"
sysctl_root="$(mktemp -d)"
iptables() {
  printf '%s\n' "iptables $*" >> "$ufw_log"
  if [[ "${1:-}" == "-S" && "${2:-}" == "ufw-before-input" ]]; then
    return 0
  fi
  if [[ "${1:-}" == "-C" ]]; then
    return 1
  fi
  return 0
}
timeout() { shift; "$@"; }
conntrack() { printf '%s\n' "conntrack $*" >> "$ufw_log"; return 0; }
VERITAS_SYSCTL_ROOT="$sysctl_root" prepare_repath eno1 wlx90de802153e1 || fail "repath firewall prep failed"
grep -q 'iptables -I ufw-before-input 1 -i veritas0 -m comment --comment veritas-tun-in -j ACCEPT' "$ufw_log" || fail "ufw-before-input does not accept veritas0"
grep -q 'iptables -I INPUT 1 -i veritas0 -m comment --comment veritas-tun-in -j ACCEPT' "$ufw_log" || fail "INPUT does not accept veritas0"
if grep '^iptables ' "$ufw_log" | grep -q -e '-i eno1' -e '-i wlx'; then
  fail "tunnel input accept was not limited to veritas0"
fi
[[ "$(cat "$sysctl_root/net/ipv4/conf/veritas0/rp_filter")" == "2" ]] || fail "veritas0 rp_filter was not loose"
[[ "$(cat "$sysctl_root/net/ipv4/conf/eno1/rp_filter")" == "2" ]] || fail "underlay rp_filter was not loose"
if [[ -e "$sysctl_root/net/ipv4/conf/all/rp_filter" ]]; then
  fail "global rp_filter was changed"
fi
grep -q 'conntrack -D -i wlx90de802153e1' "$ufw_log" || fail "old underlay conntrack was kept"
grep -q 'conntrack -D -o wlx90de802153e1' "$ufw_log" || fail "old underlay conntrack was kept"
: > "$ufw_log"
iptables() {
  printf '%s\n' "iptables $*" >> "$ufw_log"
  if [[ "${1:-}" == "-C" ]]; then
    if grep -Fq "iptables -D ${2:-}" "$ufw_log"; then
      return 1
    fi
    return 0
  fi
  return 0
}
clear_tunnel_input || fail "tunnel input cleanup failed"
grep -q 'iptables -D ufw-before-input -i veritas0 -m comment --comment veritas-tun-in -j ACCEPT' "$ufw_log" || fail "disconnect did not remove the ufw accept"
grep -q 'iptables -D INPUT -i veritas0 -m comment --comment veritas-tun-in -j ACCEPT' "$ufw_log" || fail "disconnect did not remove the INPUT accept"
rm -rf "$sysctl_root"
rm -f "$ufw_log"
unset -f ip iptables timeout conntrack
unset VERITAS_SYS_ROOT VERITAS_SYSCTL_ROOT
rm -f "$route_log"

if grep -q 'Command::new("resolvectl")' "$lib"; then
  fail "resolvectl is invoked from the desktop process"
fi
if ! grep -q 'mark_reconnect_wanted' "$lib"; then
  fail "connect does not keep reconnect intent"
fi
if ! grep -q 'clear_reconnect_wanted' "$lib"; then
  fail "disconnect does not clear reconnect intent"
fi
# clear_reconnect_wanted sits inside `if !soft`, the intentional disconnect.
clear_line="$(grep -n 'clear_reconnect_wanted' "$lib" | head -1 | cut -d: -f1)"
if ! awk -v n="$clear_line" 'NR>=n-8 && NR<=n && /if !soft/ { found=1 } END { exit !found }' "$lib"; then
  fail "reconnect intent is cleared outside an intentional disconnect"
fi

path_section="$(sed -n "/<<'SOFT_PATH'/,/^SOFT_PATH$/p" "$lib")"
follow_line="$(printf '%s\n' "$path_section" | awk '/follow_underlay_route/{print NR; exit}')"
fresh_line="$(printf '%s\n' "$path_section" | awk '/maybe_fresh_lines/{print NR; exit}')"
[[ -n "$follow_line" && -n "$fresh_line" && "$follow_line" -lt "$fresh_line" ]] || fail "path-adapt reads the peer before moving the endpoint route"
printf '%s\n' "$path_section" | grep -q 'underlay not ready' || fail "path-adapt treats a missing underlay as success"
printf '%s\n' "$path_section" | grep -q 'endpoint_route_ok' || fail "path-adapt reports success without checking the host route"
printf '%s\n' "$path_section" | grep -q 'prepare_repath' || fail "path-adapt does not prepare ufw and rp_filter"
printf '%s\n' "$path_section" | grep -q 'dns_run 3 resolvectl default-route' || fail "path-adapt resolvectl is not bounded"
if printf '%s\n' "$path_section" | grep -q 'resolvectl default-route "$OLD_IF" true >/dev/null'; then
  fail "path-adapt calls resolvectl on the old interface without a timeout"
fi
if ! grep -q -- '-k", "2", "10", "sudo", "-n", SOFT_PATH_ADAPT_HELPER' "$lib"; then
  fail "path-adapt sudo is not killed when it ignores SIGTERM"
fi
if ! grep -q -- '-k", "2", "45", "sudo", "-n", SOFT_RECONNECT_HELPER' "$lib"; then
  fail "reconnect sudo is not killed when it ignores SIGTERM"
fi

# The body saved as reconnect.sh is the bring-up script. It must compute the
# underlay when it runs, not embed the gateway or interface from connect time.
# Read the section from a file. A pipe into `grep -q` or `awk ... exit` under
# `pipefail` returns 141 when the reader closes early, and `set -e` then
# aborts the test even though the line was found.
bringup_file="$(mktemp)"
sed -n '1,/<<'\''SOFT_CLEANUP'\''/p' "$lib" > "$bringup_file"
if grep -q "awk '/default via/" "$bringup_file"; then
  rm -f "$bringup_file"
  fail "reconnect helper snapshots the connect-time gateway"
fi
grep -q 'pick_underlay "$IFACE_NAME"' "$bringup_file" || { rm -f "$bringup_file"; fail "reconnect helper does not read the current underlay"; }
grep -q 'follow_underlay_route "$ROUTE_IP"' "$bringup_file" || { rm -f "$bringup_file"; fail "reconnect helper does not pin the endpoint before the handshake"; }
grep -q 'apply_endpoint_choice' "$bringup_file" || { rm -f "$bringup_file"; fail "reconnect helper does not choose the endpoint for this underlay"; }
grep -q 'maybe_fresh_lines "$GW"' "$bringup_file" || { rm -f "$bringup_file"; fail "reconnect helper does not re-read the peer"; }
pick_line="$(awk '/pick_underlay "\$IFACE_NAME"/{print NR; exit}' "$bringup_file")"
pin_line="$(awk '/follow_underlay_route "\$ROUTE_IP"/{print NR; exit}' "$bringup_file")"
ping_line="$(awk '/ping -c 3 -W 1 10.0.0.1/{print NR; exit}' "$bringup_file")"
rm -f "$bringup_file"
[[ -n "$pick_line" && -n "$pin_line" && -n "$ping_line" && "$pick_line" -lt "$pin_line" && "$pin_line" -lt "$ping_line" ]] || fail "the endpoint host route is installed after the handshake ping"

teardown_section="$(sed -n "/<<'SOFT_TEARDOWN'/,/^SOFT_TEARDOWN$/p" "$lib")"
printf '%s\n' "$teardown_section" | grep -q 'clear_tunnel_input' || fail "disconnect leaves the veritas0 input accept"
printf '%s\n' "$teardown_section" | grep -q 'stop_path_adapt' || fail "disconnect does not stop a late path-adapt"
printf '%s\n' "$teardown_section" | grep -q 'blackhole default metric 1' || fail "disconnect leaves the blackhole route"
printf '%s\n' "$teardown_section" | grep -q '0.0.0.0/1' || fail "disconnect leaves the tunnel split route"
printf '%s\n' "$teardown_section" | grep -q 'ip link delete' || fail "disconnect leaves veritas0"
teardown_file="$(mktemp)"
printf '%s\n' "$teardown_section" > "$teardown_file"
grep -q 'release_nm_connectivity' "$teardown_file" || { rm -f "$teardown_file"; fail "disconnect does not restore NetworkManager connectivity checks"; }
rm -f "$teardown_file"
grep -q 'hold_nm_connectivity' "$lib" || fail "connect does not pause NetworkManager connectivity checks"
grep -q 'passwordless_sudoers_installed' "$lib" || fail "first connect still calls sudo before sudoers exists"
grep -q 'connect still in progress' "$lib" || fail "a connect watchdog can tear down an in-flight bring-up"
if grep -q 'sudo.args(\["-n", "bash", "-s", "--"\])' "$lib"; then
  fail "interactive elevation still tries sudo -n bash -s"
fi

printf 'ok\n'
