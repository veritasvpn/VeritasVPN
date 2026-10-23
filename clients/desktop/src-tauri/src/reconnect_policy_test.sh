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

app="$ROOT/../../src/App.tsx"
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

printf 'ok\n'
