# Endpoint and Stealth decisions shared by bring-up and path-adapt.
# A stored LAN address is never selected just because the underlay shares its /24.
# Stealth restarts at most once per network, then waits out a cooldown unless the
# tunnel is receiving bytes or the WireGuard handshake is under 180s.

is_rfc1918() {
  local ip="$1"
  [[ "$ip" =~ ^10\.([0-9]{1,3}\.){2}[0-9]{1,3}$ ]] && return 0
  [[ "$ip" =~ ^192\.168\.([0-9]{1,3}\.)[0-9]{1,3}$ ]] && return 0
  [[ "$ip" =~ ^172\.(1[6-9]|2[0-9]|3[0-1])\.([0-9]{1,3}\.)[0-9]{1,3}$ ]] && return 0
  return 1
}

host_of() {
  local ep="$1"
  printf '%s' "${ep%%:*}"
}

port_of() {
  local ep="$1" port
  [[ "$ep" == *:* ]] || return 1
  port="${ep##*:}"
  [[ "$port" =~ ^[0-9]+$ ]] || return 1
  if (( port < 1 || port > 65535 )); then
    return 1
  fi
  printf '%s' "$port"
}

same_ipv4_24() {
  local a="$1" b="$2"
  [[ "$a" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { printf 'no'; return 0; }
  [[ "$b" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { printf 'no'; return 0; }
  if [[ "${a%.*}" == "${b%.*}" ]]; then
    printf 'yes'
  else
    printf 'no'
  fi
}

trim_ep() {
  local v="$1"
  v="${v#"${v%%[![:space:]]*}"}"
  v="${v%"${v##*[![:space:]]}"}"
  printf '%s' "$v"
}

valid_endpoint() {
  [[ "$1" =~ ^[A-Za-z0-9.-]+:[0-9]{1,5}$ ]]
}

public_endpoint() {
  local ep host
  ep="$(trim_ep "$1")"
  [[ -n "$ep" ]] || return 0
  valid_endpoint "$ep" || return 0
  host="$(host_of "$ep")"
  if is_rfc1918 "$host"; then
    return 0
  fi
  printf '%s' "$ep"
}

# choose_underlay ADVERTISED WAN UNDERLAY_SRC
# Prints "<endpoint> lan|public" or " none".
# ADVERTISED is server_endpoint from a peer read on this underlay. Empty means
# that read has not happened, so the public endpoint is used.
choose_underlay() {
  local advertised wan src wan_ep host
  advertised="$(trim_ep "$1")"
  wan="$(trim_ep "$2")"
  src="$(trim_ep "$3")"
  if [[ -n "$advertised" ]] && ! valid_endpoint "$advertised"; then
    advertised=""
  fi
  wan_ep="$(public_endpoint "$wan")"
  if [[ -z "$wan_ep" ]]; then
    wan_ep="$(public_endpoint "$advertised")"
  fi
  if [[ -n "$advertised" ]]; then
    host="$(host_of "$advertised")"
    if is_rfc1918 "$host" && [[ -n "$src" ]] && [[ "$(same_ipv4_24 "$host" "$src")" == yes ]]; then
      printf '%s lan\n' "$advertised"
      return 0
    fi
  fi
  if [[ -n "$wan_ep" ]]; then
    printf '%s public\n' "$wan_ep"
    return 0
  fi
  printf ' none\n'
}

# choose_stealth ADVERTISED_UDP FRESH_STEALTH STORED_STEALTH WAN UNDERLAY_SRC
# Prints the host:port to dial, or an empty line.
choose_stealth() {
  local advertised fresh stored wan src base on_lan adv_host host wan_host port
  advertised="$(trim_ep "$1")"
  fresh="$(trim_ep "$2")"
  stored="$(trim_ep "$3")"
  wan="$(trim_ep "$4")"
  src="$(trim_ep "$5")"
  [[ -n "$advertised" ]] && valid_endpoint "$advertised" || advertised=""
  [[ -n "$fresh" ]] && valid_endpoint "$fresh" || fresh=""
  [[ -n "$stored" ]] && valid_endpoint "$stored" || stored=""
  base="${fresh:-$stored}"
  [[ -n "$base" ]] || { printf '\n'; return 0; }

  on_lan=0
  adv_host=""
  if [[ -n "$advertised" ]]; then
    adv_host="$(host_of "$advertised")"
    if is_rfc1918 "$adv_host" && [[ -n "$src" ]] && [[ "$(same_ipv4_24 "$adv_host" "$src")" == yes ]]; then
      on_lan=1
    fi
  fi

  if [[ "$on_lan" == 1 && -n "$adv_host" ]]; then
    port="$(port_of "$base" 2>/dev/null || true)"
    if [[ -z "$port" ]]; then
      port="$(port_of "$stored" 2>/dev/null || true)"
    fi
    if [[ -n "$port" ]]; then
      printf '%s:%s\n' "$adv_host" "$port"
      return 0
    fi
  fi

  host="$(host_of "$base")"
  if ! is_rfc1918 "$host"; then
    printf '%s\n' "$base"
    return 0
  fi

  wan_host=""
  if [[ -n "$wan" ]] && valid_endpoint "$wan"; then
    wan_host="$(host_of "$wan")"
  fi
  if [[ -n "$wan_host" ]] && ! is_rfc1918 "$wan_host"; then
    port="$(port_of "$base" 2>/dev/null || true)"
    if [[ -n "$port" ]]; then
      printf '%s:%s\n' "$wan_host" "$port"
      return 0
    fi
  fi
  printf '\n'
}

# wstunnel_prepare_line REMOTE RESOLVED_IP
# Prints "url <wss://...>" and "sni <host-or-empty>".
# When the remote is a name, dial the resolved address so the /32 pin and the
# socket cannot disagree. SNI stays the original name.
wstunnel_prepare_line() {
  local remote ip host port
  remote="$(trim_ep "$1")"
  ip="$(trim_ep "$2")"
  host="$(host_of "$remote")"
  port="$(port_of "$remote" 2>/dev/null || true)"
  if [[ -z "$port" ]]; then
    printf 'url wss://%s\n' "$remote"
    printf 'sni \n'
    return 0
  fi
  if [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ && "$host" != "$ip" ]]; then
    printf 'url wss://%s:%s\n' "$ip" "$port"
    printf 'sni %s\n' "$host"
    return 0
  fi
  printf 'url wss://%s\n' "$remote"
  printf 'sni \n'
}

# Peer address of a running wstunnel, or empty. Used to pin the /32 to the
# address the process actually connected to.
actual_wstunnel_ip() {
  local pid="$1" peer
  [[ "$pid" =~ ^[0-9]+$ ]] || return 0
  command -v ss >/dev/null 2>&1 || return 0
  peer="$(ss -H -tnp 2>/dev/null | awk -v pid="$pid" '
    index($0, "pid=" pid) {
      addr = $5
      sub(/%[^:]*$/, "", addr)
      sub(/:[0-9]+$/, "", addr)
      if (addr ~ /^([0-9]{1,3}\.){3}[0-9]{1,3}$/) { print addr; exit }
    }')"
  [[ "$peer" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 0
  printf '%s' "$peer"
}

# stealth_decide LIVE BOUND OPEN DIALING RX TX HANDSHAKE_SEC NOW_SEC \
#   ISSUED ISSUED_AT BASELINE_RX DIALS FORCE
# Prints "ACTION<TAB>issued<TAB>issued_at<TAB>baseline_rx<TAB>dials".
# OPEN, DIALING, and FORCE are 1 or 0. Handshake age is in seconds.
# Received bytes or a handshake under 180s means the tunnel is healthy.
# A different gateway|interface dials once. The same network waits 30s.
stealth_decide() {
  local live="$1" bound="$2" open="$3" dialing="$4" rx="$5" tx="$6"
  local handshake_sec="$7" now_sec="$8"
  local issued="$9" issued_at="${10}" baseline_rx="${11}" dials="${12}" force="${13}"
  local on_network=0 received=0 handshake_fresh=0 age since
  local moved=0 unbound=0 stalled=0

  [[ "$rx" =~ ^[0-9]+$ ]] || rx=0
  [[ "$tx" =~ ^[0-9]+$ ]] || tx=0
  [[ "$baseline_rx" =~ ^[0-9]+$ ]] || baseline_rx=0
  [[ "$handshake_sec" =~ ^[0-9]+$ ]] || handshake_sec=0
  [[ "$now_sec" =~ ^[0-9]+$ ]] || now_sec=0
  [[ "$issued_at" =~ ^[0-9]+$ ]] || issued_at=0
  [[ "$dials" =~ ^[0-9]+$ ]] || dials=0
  : "$tx"

  if [[ -z "$live" ]]; then
    printf 'WAIT\t%s\t%s\t%s\t%s\n' "$issued" "$issued_at" "$baseline_rx" "$dials"
    return 0
  fi

  [[ -n "$bound" && "$bound" == "$live" ]] && on_network=1
  if (( rx > baseline_rx )); then
    received=1
  fi
  if (( handshake_sec > 0 )); then
    age=$(( now_sec - handshake_sec ))
    if (( age >= 0 && age <= 180 )); then
      handshake_fresh=1
    fi
  fi

  if [[ "$on_network" == 1 && "$open" == 1 && "$dialing" != 1 && ( "$received" == 1 || "$handshake_fresh" == 1 ) ]]; then
    printf 'HEALTHY\t%s\t%s\t%s\t%s\n' "$live" "$now_sec" "$rx" "$dials"
    return 0
  fi

  since=999999
  if [[ -n "$issued" && "$issued" == "$live" && "$issued_at" -gt 0 ]]; then
    since=$(( now_sec - issued_at ))
  fi
  if [[ -n "$issued" && "$issued" == "$live" && "$since" -lt 30 ]]; then
    printf 'WAIT\t%s\t%s\t%s\t%s\n' "$issued" "$issued_at" "$baseline_rx" "$dials"
    return 0
  fi

  [[ -n "$bound" && "$bound" != "$live" ]] && moved=1
  [[ -z "$bound" && "$dialing" != 1 ]] && unbound=1
  if [[ "$force" == 1 && "$received" != 1 && "$handshake_fresh" != 1 && "$dialing" != 1 ]]; then
    stalled=1
  fi
  if [[ "$moved" == 1 || "$unbound" == 1 || "$stalled" == 1 ]]; then
    printf 'DIAL\t%s\t%s\t%s\t%s\n' "$live" "$now_sec" "$rx" "$(( dials + 1 ))"
    return 0
  fi
  printf 'WAIT\t%s\t%s\t%s\t%s\n' "$issued" "$issued_at" "$baseline_rx" "$dials"
}

resolve_ipv4() {
  local host="$1" ip=""
  if [[ "$host" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
    printf '%s' "$host"
    return 0
  fi
  [[ "$host" =~ ^[A-Za-z0-9.-]+$ ]] || return 0
  if command -v dig >/dev/null 2>&1; then
    ip="$(dig +time=1 +tries=1 +short "$host" A 2>/dev/null | awk '/^([0-9]{1,3}\.){3}[0-9]{1,3}$/ { print; exit }')"
  fi
  if [[ -z "$ip" ]] && command -v getent >/dev/null 2>&1; then
    ip="$(getent ahostsv4 "$host" 2>/dev/null | awk '{ print $1; exit }')"
  fi
  [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || ip=""
  printf '%s' "$ip"
}

# Sets CHOOSE_IP, CHOOSE_EP, STEALTH, STEALTH_DIAL, RESOLVED_STEALTH_IP.
# Requires FRESH_EP, FRESH_STEALTH, ENDPOINT_WAN, STEALTH_REMOTE, ENDPOINT,
# ENDPOINT_IP, and NEW_SRC. Does not consult a stored LAN field.
apply_endpoint_choice() {
  local choice kind ep stealth_ep host ip
  STEALTH="${STEALTH_REMOTE:-}"
  STEALTH_DIAL=""
  RESOLVED_STEALTH_IP=""
  if [[ -z "$STEALTH" ]]; then
    choice="$(choose_underlay "${FRESH_EP:-}" "${ENDPOINT_WAN:-}" "${NEW_SRC:-}")"
    ep="${choice% *}"
    kind="${choice##* }"
    if [[ "$kind" == lan || "$kind" == public ]]; then
      CHOOSE_EP="$ep"
      CHOOSE_IP="$(host_of "$ep")"
    else
      CHOOSE_IP="${ENDPOINT_IP:-}"
      CHOOSE_EP="${ENDPOINT:-${CHOOSE_IP}:51820}"
      if [[ -n "$CHOOSE_IP" ]] && is_rfc1918 "$CHOOSE_IP"; then
        ep="$(public_endpoint "${ENDPOINT_WAN:-}")"
        if [[ -n "$ep" ]]; then
          CHOOSE_EP="$ep"
          CHOOSE_IP="$(host_of "$ep")"
        fi
      fi
    fi
    return 0
  fi

  stealth_ep="$(choose_stealth "${FRESH_EP:-}" "${FRESH_STEALTH:-}" "$STEALTH" "${ENDPOINT_WAN:-}" "${NEW_SRC:-}")"
  stealth_ep="$(trim_ep "$stealth_ep")"
  if [[ -z "$stealth_ep" ]]; then
    stealth_ep="$STEALTH"
  fi
  host="$(host_of "$stealth_ep")"
  ip="$(resolve_ipv4 "$host")"
  if [[ -n "$ip" ]]; then
    CHOOSE_IP="$ip"
  else
    CHOOSE_IP="${ENDPOINT_IP:-}"
  fi
  CHOOSE_EP="127.0.0.1:41820"
  STEALTH_DIAL="$stealth_ep"
  RESOLVED_STEALTH_IP="$ip"
}
