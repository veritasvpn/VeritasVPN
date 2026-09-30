#!/usr/bin/env bash
# Probe the production VPN gateway's public egress and publish JSON for
# GET https://api.veritasvpn.cloud/api/v1/status/egress.
#
# Lightweight path (default), run on the VPN node (Dell):
#   1. VERITAS_PUBLIC_IP is a public IPv4 address (not a Cloudflare CDN record).
#   2. The WireGuard interface is administratively up.
#   3. nftables masquerade from that interface to the uplink is installed
#      (the same NAT path client traffic uses).
#   4. An IPv4 echo bound to that uplink matches VERITAS_PUBLIC_IP.
# This does not open a client tunnel and does not call /api/check/ip.
#
# Fallback when a client still exits wrong after this check is green:
#   sudo bash deploy/verify/tunnel-hold-e2e.sh
# That brings up a real WireGuard tunnel and compares api.ipify.org to the
# peer endpoint. It needs root, wg-quick, and VERITAS_E2E_ACCOUNT_ID.
#
# Cron (Dell installs this; nothing in CI SSHes to the host), every 5 minutes
# so a 15-minute stale window on /status survives a missed run:
#   */5 * * * * root /opt/veritasvpn/deploy/ops/verify-vpn-egress.sh >> /var/log/veritas-egress.log 2>&1
# See deploy/ops/VPN_EGRESS.md.
set -euo pipefail

# Cron often ships PATH=/usr/bin:/bin, which hides ip(8) and nft in /usr/sbin.
# Append those directories so a caller-supplied PATH (and its tools) stays first.
PATH="${PATH:-/usr/bin:/bin}"
case ":$PATH:" in
  *:/usr/sbin:*) ;;
  *) PATH="${PATH}:/usr/local/sbin:/usr/sbin:/sbin" ;;
esac

ENV_FILE="${VERITAS_EGRESS_ENV:-/etc/veritasvpn/egress.env}"
STATUS_EGRESS_PATH="${STATUS_EGRESS_PATH:-/var/lib/veritasvpn/status/egress.json}"
WG_IFACE="${WG_INTERFACE:-wg0}"
NFT_TABLE="${NFT_TABLE:-veritas}"
EXPECTED_IP="${VERITAS_PUBLIC_IP:-${PUBLIC_IP:-${EXPECTED_EGRESS_IP:-}}}"
EGRESS_IFACE="${EGRESS_IFACE:-}"
IP_ECHO_URLS="${IP_ECHO_URLS:-https://api.ipify.org https://ipv4.icanhazip.com https://ifconfig.me/ip}"

OK=false
OBSERVED=""
ERROR=""
PUBLISHED=0

valid_iface() {
  [[ "$1" =~ ^[A-Za-z0-9_.:-]{1,15}$ ]]
}

valid_ipv4() {
  local ip="$1" oct
  [[ "$ip" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || return 1
  for oct in "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}" "${BASH_REMATCH[4]}"; do
    ((10#$oct <= 255)) || return 1
  done
}

# Public egress cannot be loopback, RFC1918, link-local, CGNAT, or multicast.
is_public_ipv4() {
  local ip="$1" a b
  valid_ipv4 "$ip" || return 1
  IFS=. read -r a b _ _ <<<"$ip"
  if ((a == 0 || a == 10 || a == 127 || a >= 224)); then
    return 1
  fi
  if ((a == 169 && b == 254)); then
    return 1
  fi
  if ((a == 172 && b >= 16 && b <= 31)); then
    return 1
  fi
  if ((a == 192 && b == 168)); then
    return 1
  fi
  if ((a == 100 && b >= 64 && b <= 127)); then
    return 1
  fi
  return 0
}

detect_egress_iface() {
  ip -4 route show default 2>/dev/null | awk '
    {
      dev = ""
      metric = 0
      seen_metric = 0
      for (i = 1; i <= NF; i++) {
        if ($i == "dev") dev = $(i + 1)
        if ($i == "metric") { metric = $(i + 1); seen_metric = 1 }
      }
      if (!seen_metric) metric = 0
      if (dev != "" && (best == "" || metric + 0 < best_metric)) {
        best = dev
        best_metric = metric + 0
      }
    }
    END { if (best != "") print best }
  '
}

wg_is_up() {
  local line
  line="$(ip link show "$WG_IFACE" 2>/dev/null || true)"
  [[ "$line" == *",UP"* ]]
}

nat_rule_present() {
  local rules needle
  rules="$(nft list table inet "$NFT_TABLE" 2>/dev/null)" || return 1
  needle="iifname \"${WG_IFACE}\" oifname \"${EGRESS_IFACE}\" masquerade"
  grep -F -q -- "$needle" <<<"$rules"
}

read_egress_ip() {
  local url body
  local -a urls=()
  read -r -a urls <<<"$IP_ECHO_URLS"
  for url in "${urls[@]}"; do
    [[ "$url" == https://* ]] || continue
    if ! body="$(curl --fail --silent --show-error --max-time 12 -4 --interface "$EGRESS_IFACE" "$url")"; then
      continue
    fi
    body="${body//$'\r'/}"
    body="${body//$'\n'/}"
    body="${body//[[:space:]]/}"
    if is_public_ipv4 "$body"; then
      printf '%s' "$body"
      return 0
    fi
  done
  return 1
}

apply_env_assignment() {
  local key="$1" value="$2" len first last
  len=${#value}
  if ((len >= 2)); then
    first="${value:0:1}"
    last="${value:len-1:1}"
    if [[ "$first" == "$last" && ( "$first" == '"' || "$first" == "'" ) ]]; then
      value="${value:1:len-2}"
    fi
  fi
  case "$key" in
    VERITAS_PUBLIC_IP) VERITAS_PUBLIC_IP="$value" ;;
    PUBLIC_IP) PUBLIC_IP="$value" ;;
    EXPECTED_EGRESS_IP) EXPECTED_EGRESS_IP="$value" ;;
    EGRESS_IFACE) EGRESS_IFACE="$value" ;;
    WG_INTERFACE) WG_INTERFACE="$value" ;;
    NFT_TABLE) NFT_TABLE="$value" ;;
    STATUS_EGRESS_PATH) STATUS_EGRESS_PATH="$value" ;;
    IP_ECHO_URLS) IP_ECHO_URLS="$value" ;;
    *)
      ERROR="unsupported variable ${key} in env file"
      return 2
      ;;
  esac
}

load_env_file() {
  local file="$1" mode line key value
  [[ -f "$file" ]] || return 0
  if [[ ! -r "$file" ]]; then
    ERROR="cannot read ${file}"
    return 2
  fi
  if [[ "$(id -u)" -eq 0 ]]; then
    mode="$(stat -c '%a' "$file")"
    if [[ "$(stat -c '%u' "$file")" -ne 0 ]]; then
      ERROR="refusing to read ${file} (not owned by root)"
      return 2
    fi
    if (( (8#$mode & 022) != 0 )); then
      ERROR="refusing to read ${file} (group or world writable)"
      return 2
    fi
  fi
  # Assignments only. Do not source the file: it runs as root from cron.
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line#"${line%%[![:space:]]*}"}"
    [[ -z "$line" || "$line" == \#* ]] && continue
    if [[ "$line" =~ ^export[[:space:]]+([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      key="${BASH_REMATCH[1]}"
      value="${BASH_REMATCH[2]}"
    elif [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      key="${BASH_REMATCH[1]}"
      value="${BASH_REMATCH[2]}"
    else
      ERROR="invalid line in ${file}"
      return 2
    fi
    apply_env_assignment "$key" "$value" || return 2
  done <"$file"
  WG_IFACE="${WG_INTERFACE:-wg0}"
  NFT_TABLE="${NFT_TABLE:-veritas}"
  EXPECTED_IP="${VERITAS_PUBLIC_IP:-${PUBLIC_IP:-${EXPECTED_EGRESS_IP:-}}}"
  EGRESS_IFACE="${EGRESS_IFACE:-}"
  IP_ECHO_URLS="${IP_ECHO_URLS:-https://api.ipify.org https://ipv4.icanhazip.com https://ifconfig.me/ip}"
  STATUS_EGRESS_PATH="${STATUS_EGRESS_PATH:-/var/lib/veritasvpn/status/egress.json}"
}

emit_json() {
  local checked_at
  checked_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  python3 -c 'import json,sys
ok, observed, expected, checked, err = sys.argv[1:]
print(json.dumps({
  "ok": ok == "true",
  "observed_ip": observed,
  "expected_ip": expected,
  "checked_at": checked,
  "error": err,
}, separators=(",", ":")))' \
    "$OK" "$OBSERVED" "$EXPECTED_IP" "$checked_at" "$ERROR"
}

publish() {
  local json dir tmp
  [[ "$PUBLISHED" -eq 1 ]] && return 0
  PUBLISHED=1
  if [[ "$OK" == true ]]; then
    printf 'VPN egress OK observed=%s\n' "$OBSERVED" >&2
  else
    printf 'VPN egress FAIL: %s\n' "${ERROR:-unknown}" >&2
  fi
  if ! command -v python3 >/dev/null 2>&1; then
    printf 'python3 is required to publish egress JSON\n' >&2
    return 1
  fi
  json="$(emit_json)" || return 1
  if [[ "$STATUS_EGRESS_PATH" != /*.json || "$STATUS_EGRESS_PATH" == *".."* ]]; then
    printf 'refusing to write STATUS_EGRESS_PATH=%s\n' "$STATUS_EGRESS_PATH" >&2
    printf '%s\n' "$json"
    return 1
  fi
  dir="$(dirname "$STATUS_EGRESS_PATH")"
  mkdir -p "$dir"
  # nginx in the cluster reads this directory as uid 101. Ignore failure when
  # the parent already exists and is not owned by this user (for example /tmp).
  chmod 755 "$dir" 2>/dev/null || true
  tmp="$(mktemp "${STATUS_EGRESS_PATH}.tmp.XXXXXX")"
  printf '%s\n' "$json" >"$tmp"
  chmod 644 "$tmp"
  mv -f "$tmp" "$STATUS_EGRESS_PATH"
  printf '%s\n' "$json"
}

on_exit() {
  local rc=$?
  trap - EXIT
  if ! publish; then
    # Keep a probe/config failure. A successful probe that cannot be published
    # is still a failure.
    [[ "$rc" -eq 0 ]] && rc=1
  fi
  exit "$rc"
}

probe() {
  if ! command -v ip >/dev/null 2>&1; then
    ERROR="iproute2 is required"
    return 2
  fi
  if ! command -v nft >/dev/null 2>&1; then
    ERROR="nft is required to verify the NAT path"
    return 2
  fi
  if ! command -v curl >/dev/null 2>&1; then
    ERROR="curl is required"
    return 2
  fi
  if ! valid_iface "$WG_IFACE"; then
    ERROR="WG_INTERFACE is invalid"
    return 2
  fi
  if ! valid_iface "$NFT_TABLE"; then
    ERROR="NFT_TABLE is invalid"
    return 2
  fi
  if ! valid_ipv4 "$EXPECTED_IP"; then
    ERROR="VERITAS_PUBLIC_IP is missing or not an IPv4 address"
    return 2
  fi
  if ! is_public_ipv4 "$EXPECTED_IP"; then
    ERROR="VERITAS_PUBLIC_IP is not a public IPv4 address"
    return 2
  fi
  if [[ -z "$EGRESS_IFACE" ]]; then
    EGRESS_IFACE="$(detect_egress_iface)"
  fi
  if ! valid_iface "$EGRESS_IFACE" || ! ip link show "$EGRESS_IFACE" >/dev/null 2>&1; then
    ERROR="egress interface not found"
    return 1
  fi
  if ! wg_is_up; then
    ERROR="wireguard interface ${WG_IFACE} is down"
    return 1
  fi
  if ! nat_rule_present; then
    ERROR="nft masquerade rule missing (${WG_IFACE} -> ${EGRESS_IFACE})"
    return 1
  fi
  if ! OBSERVED="$(read_egress_ip)"; then
    OBSERVED=""
    ERROR="ip echo via ${EGRESS_IFACE} failed"
    return 1
  fi
  if [[ "$OBSERVED" != "$EXPECTED_IP" ]]; then
    ERROR="egress mismatch: observed ${OBSERVED} expected ${EXPECTED_IP}"
    return 1
  fi
  OK=true
  ERROR=""
  return 0
}

main() {
  local probe_rc=0
  trap on_exit EXIT
  if ! load_env_file "$ENV_FILE"; then
    exit 2
  fi
  # The env file is sourced. Do not let it preset the probe result.
  OK=false
  OBSERVED=""
  ERROR=""
  PUBLISHED=0
  if [[ "$STATUS_EGRESS_PATH" != /*.json || "$STATUS_EGRESS_PATH" == *".."* ]]; then
    ERROR="STATUS_EGRESS_PATH must be an absolute .json path"
    exit 2
  fi
  probe || probe_rc=$?
  exit "$probe_rc"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main
fi
