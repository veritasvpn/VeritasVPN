#!/usr/bin/env bash
# Phase 0 repro harness for Linux network-switch recovery.
#
# Usage:
#   bash clients/desktop/scripts/network-switch-repro.sh baseline
#   bash clients/desktop/scripts/network-switch-repro.sh after
#
# After a network switch, run `after` without Disconnect. Paste the report
# into chat or save it for Phase 0 analysis.
#
# Always writes a report under /tmp/veritas-repro/ even if individual capture
# commands fail. Never uses set -e for capture sections so a dead underlay
# cannot produce a silent/empty report.

LABEL="${1:-baseline}"
STATE_DIR="${VERITAS_STATE_DIR:-${HOME}/.veritasvpn}"
REPORT_DIR="${VERITAS_REPRO_DIR:-/tmp/veritas-repro}"
mkdir -p "$REPORT_DIR" 2>/dev/null || true
OUT="$REPORT_DIR/network-switch-repro-${LABEL}.txt"
umask 077

ts() { date -u +"%Y-%m-%dT%H:%M:%SZ"; }

# Safe capture of a single command (timeout + never abort the whole harness).
safe_cmd() {
  local timeout_s="${1:-5}"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout --signal=TERM --kill-after=2s "${timeout_s}" "$@" 2>/dev/null || true
  else
    "$@" 2>/dev/null || true
  fi
}

detect_underlay() {
  local iface="${1:-veritas0}"
  # Prefer unicast underlay default (skip blackhole + tunnel iface), matching
  # refresh_endpoint_route_linux().
  local GW GW_IF
  GW="$(safe_cmd 3 ip -4 route show default | awk -v iface="$iface" '
    /blackhole/ { next }
    /via/ {
      for (i = 1; i <= NF; i++) if ($i == "dev") { d = $(i+1); break }
      if (d == "" || d != iface) { print $3; exit }
    }
  ')"
  GW_IF="$(safe_cmd 3 ip -4 route show default | awk -v iface="$iface" '
    /blackhole/ { next }
    /via/ {
      for (i = 1; i <= NF; i++) if ($i == "dev") { d = $(i+1); break }
      if (d == "" || d != iface) { print d; exit }
    }
  ')"
  printf '%s %s\n' "$GW" "$GW_IF"
}

# Full capture — never hard-fail the whole script.
{
  echo "===== NETWORK SWITCH REPRO (${LABEL}) ====="
  echo "timestamp: $(ts)"
  echo "hostname: $(hostname 2>/dev/null || true)"
  echo "user: $(id -un 2>/dev/null || true) uid=$(id -u)"
  echo

  echo "--- interface list ---"
  safe_cmd 3 ip -br link
  echo

  echo "--- addresses ---"
  safe_cmd 3 ip -br addr
  echo

  echo "--- main routes ---"
  safe_cmd 3 ip -4 route show table main
  echo

  echo "--- default routes ---"
  safe_cmd 3 ip -4 route show default
  echo

  echo "--- blackhole routes ---"
  safe_cmd 3 ip -4 route show blackhole
  echo

  echo "--- ipv6 defaults ---"
  safe_cmd 3 ip -6 route show default
  echo

  echo "--- blackhole ipv6 ---"
  safe_cmd 3 ip -6 route show blackhole
  echo

  echo "--- nft killswitch ---"
  safe_cmd 3 nft list table inet veritasvpn_killswitch || echo "(no nft table or no permission)"
  echo

  echo "--- iptables OUTPUT chain ---"
  safe_cmd 3 iptables -L OUTPUT -n | head -40 || echo "(no iptables)"
  echo

    echo "--- state dir ---"
    if [[ -d "$STATE_DIR" ]]; then
      ls -la "$STATE_DIR" 2>/dev/null || true
      echo
      echo "--- iface.meta ---"
      cat "$STATE_DIR/iface.meta" 2>/dev/null || true
      echo
      echo "--- iface ---"
      cat "$STATE_DIR/iface" 2>/dev/null || true
      echo
      echo "--- iface.meta.dns ---"
      cat "$STATE_DIR/iface.meta.dns" 2>/dev/null || true
      echo
      echo "--- veritas.conf ---"
      cat "$STATE_DIR/veritas.conf" 2>/dev/null || true
      echo
      echo "--- wg.conf (keys redacted) ---"
      if [[ -f "$STATE_DIR/wg.conf" ]]; then
        awk '
          /^PrivateKey/ { print "PrivateKey = [REDACTED]" }
          /^PresharedKey/ { print "PresharedKey = [REDACTED]" }
          /^\[/ { print }
          /^PublicKey/ { print "PublicKey = " $2 }
          /^Endpoint/ { print "Endpoint = " $2 }
          /^AllowedIPs/ { print "AllowedIPs = " $2 }
          /^PersistentKeepalive/ { print "PersistentKeepalive = " $2 }
          { if ($0 !~ /PrivateKey|PresharedKey/) print }
        ' "$STATE_DIR/wg.conf" 2>/dev/null || true
      else
        echo "(missing)"
      fi
    else
      echo "no $STATE_DIR"
    fi
    echo

  echo "--- resolv.conf ---"
  if [[ -f /etc/resolv.conf ]]; then
    cat /etc/resolv.conf
  else
    echo "(missing)"
  fi
  echo

  echo "--- public egress ---"
  for u in \
    "https://api.ipify.org" \
    "https://ifconfig.co" \
    "https://ipinfo.io/json"
  do
    echo "-- $u"
    curl -fsS --max-time 3 "$u" 2>/dev/null || echo "FAIL"
    echo
  done
  echo

  echo "--- endpoint host routes ---"
  ENDPOINT_IP=""
  GATEWAY=""
  IFACE=""
  if [[ -f "$STATE_DIR/iface.meta" ]]; then
    ENDPOINT_IP="$(awk -F= '/^endpoint_ip=/{print $2}' "$STATE_DIR/iface.meta" | tr -d '\r')"
    GATEWAY="$(awk -F= '/^gateway=/{print $2}' "$STATE_DIR/iface.meta" | tr -d '\r')"
    IFACE="$(awk -F= '/^iface=/{print $2}' "$STATE_DIR/iface.meta" | tr -d '\r')"
  fi
  if [[ -n "${ENDPOINT_IP:-}" ]]; then
    echo "endpoint_ip=$ENDPOINT_IP"
    echo "gateway_meta=$GATEWAY"
    echo "iface_meta=$IFACE"
    echo "--- route get ---"
    safe_cmd 3 ip route get "$ENDPOINT_IP" || true
    echo "--- route show ---"
    safe_cmd 3 ip route show "$ENDPOINT_IP" || true
    echo
  else
    echo "no endpoint_ip in meta"
  fi
  echo

  echo "--- process list ---"
  safe_cmd 3 pgrep -a wireguard-go || true
  safe_cmd 3 pgrep -a wstunnel || true
  safe_cmd 3 pgrep -a veritasvpn || true
  echo

  echo "--- wg dump ---"
  if [[ -n "${IFACE:-}" ]]; then
    safe_cmd 3 wg show "$IFACE" || true
    safe_cmd 3 wg show "$IFACE" dump || true
  else
    safe_cmd 3 wg show || true
  fi
  echo

  echo "--- path health (endpoint, gateway, kill switch) ---"
  if [[ -n "${ENDPOINT_IP:-}" ]]; then
    UNDERLAY_OUT="$(detect_underlay "${IFACE:-veritas0}")"
    UNDERLAY_GW="$(echo "$UNDERLAY_OUT" | awk '{print $1}')"
    UNDERLAY_IF="$(echo "$UNDERLAY_OUT" | awk '{print $2}')"
    echo "detected_underlay_gw=${UNDERLAY_GW:-"(none)"}"
    echo "detected_underlay_if=${UNDERLAY_IF:-"(none)"}"
    echo "meta_gateway=${GATEWAY:-"(none)"}"
    if [[ -n "$GATEWAY" && -n "$UNDERLAY_GW" && "$GATEWAY" != "$UNDERLAY_GW" ]]; then
      echo "FLAG: gateway_changed (meta=$GATEWAY underlay=$UNDERLAY_GW)"
    elif [[ -z "$UNDERLAY_GW" ]]; then
      echo "FLAG: underlay_gateway_not_detected"
    else
      echo "FLAG: gateway_meta_matches_underlay"
    fi
  else
    echo "FLAG: no_endpoint_ip_in_meta"
  fi
  echo

  echo "--- notes ---"
  echo "Fill in after switch: what changed in UI, routes, DNS?"
  echo "timestamp_end: $(ts)"
} >"$OUT"

# One-line summary for quick paste
python3 - <<PY 2>/dev/null || true
import pathlib
out = pathlib.Path("$OUT")
print("Wrote", out)
text = out.read_text() if out.exists() else ""
print("report_bytes=", len(text))
if not text.strip():
    print("EMPTY_REPORT: capture produced no content")
for line in text.splitlines():
    if (
        line.startswith("FLAG:")
        or line.startswith("timestamp:")
        or line.startswith("endpoint_ip=")
        or line.startswith("gateway_meta=")
        or line.startswith("detected_underlay")
        or line.startswith("---")
        or "wireguard" in line.lower()
        or "FAIL" in line
    ):
        print(line)
PY

echo "Report ready: $OUT"
if [[ -f "$OUT" ]]; then
  echo "--- stdout summary ---"
  if grep -qE 'FLAG:|endpoint_ip=|detected_underlay|no endpoint|timestamp:|EMPTY_REPORT' "$OUT" 2>/dev/null; then
    grep -E 'FLAG:|endpoint_ip=|detected_underlay|no endpoint|timestamp:|EMPTY_REPORT|report_bytes' "$OUT" | head -40
  else
    echo "report has no key flags (inspect $OUT)"
  fi
fi
