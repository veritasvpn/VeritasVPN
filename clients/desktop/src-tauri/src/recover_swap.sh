# Swap the live WireGuard peer without removing the kill switch.
# Temporary API accepts are marked veritas-recovery and removed on close.
# This file is sourced. It does not take the tunnel down.

recovery_uid() {
  local uid=""
  if [[ -n "${RECOVERY_UID_FORCE:-}" ]]; then
    [[ "$RECOVERY_UID_FORCE" =~ ^[0-9]+$ ]] || return 1
    printf '%s' "$RECOVERY_UID_FORCE"
    return 0
  fi
  [[ -n "${SUDO_USER:-}" && "$SUDO_USER" != "root" ]] || return 1
  uid="$(id -u "$SUDO_USER" 2>/dev/null || true)"
  [[ "$uid" =~ ^[0-9]+$ ]] || return 1
  printf '%s' "$uid"
}

recovery_home() {
  local home=""
  [[ -n "${SUDO_USER:-}" && "$SUDO_USER" != "root" ]] || return 1
  home="$(getent passwd "$SUDO_USER" 2>/dev/null | cut -d: -f6)"
  [[ "$home" == /* && "$home" != *..* ]] || return 1
  printf '%s' "$home"
}

recovery_state_dir() {
  local uid=""
  uid="$(recovery_uid)" || return 1
  printf '/var/lib/veritasvpn/state/%s' "$uid"
}

recovery_request_value() {
  local key="$1" home="" file="" value=""
  home="$(recovery_home)" || return 0
  file="$home/.veritasvpn/recover-request"
  [[ -f "$file" && ! -L "$file" ]] || return 0
  value="$(awk -F= -v wanted="$key" '$1 == wanted { print substr($0, index($0, "=") + 1); exit }' "$file" 2>/dev/null || true)"
  printf '%s' "$value"
}

recovery_meta_value() {
  local key="$1" dir="" meta=""
  dir="$(recovery_state_dir)" || return 0
  meta="$dir/iface.meta"
  [[ -f "$meta" && ! -L "$meta" ]] || return 0
  awk -F= -v wanted="$key" '$1 == wanted { print substr($0, index($0, "=") + 1); exit }' "$meta" 2>/dev/null || true
}

recovery_is_ipv4() {
  [[ "$1" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]
}

recovery_valid_iface() {
  [[ "$1" =~ ^[A-Za-z0-9_.:-]{1,15}$ ]]
}

recovery_valid_ep() {
  [[ "$1" =~ ^[A-Za-z0-9.-]+:[0-9]{1,5}$ ]]
}

delete_comment_rules() {
  local comment="$1" handle="" line="" spec="" _i
  if command -v nft >/dev/null 2>&1 && nft list table inet veritasvpn_killswitch >/dev/null 2>&1; then
    for _i in 1 2 3 4 5 6 7 8; do
      handle="$(nft -a list chain inet veritasvpn_killswitch output 2>/dev/null | awk -v c="$comment" '
        index($0, "comment \"" c "\"") {
          for (i = 1; i <= NF; i++) if ($i == "handle") { print $(i + 1); exit }
        }')"
      [[ "$handle" =~ ^[0-9]+$ ]] || break
      nft delete rule inet veritasvpn_killswitch output handle "$handle" >/dev/null 2>&1 || break
    done
  fi
  if command -v iptables >/dev/null 2>&1 && iptables -L VERITASVPN_KILLSWITCH >/dev/null 2>&1; then
    while IFS= read -r line; do
      [[ "$line" == *"comment $comment"* ]] || continue
      spec="${line#-A VERITASVPN_KILLSWITCH }"
      # shellcheck disable=SC2086
      iptables -D VERITASVPN_KILLSWITCH $spec >/dev/null 2>&1 || true
    done < <(iptables -S VERITASVPN_KILLSWITCH 2>/dev/null || true)
  fi
}

insert_recovery_accept() {
  local ip="$1"
  recovery_is_ipv4 "$ip" || return 0
  if command -v nft >/dev/null 2>&1 && nft list table inet veritasvpn_killswitch >/dev/null 2>&1; then
    nft insert rule inet veritasvpn_killswitch output ip daddr "$ip" tcp dport 443 accept comment \"veritas-recovery\" >/dev/null 2>&1 || true
  fi
  if command -v iptables >/dev/null 2>&1 && iptables -L VERITASVPN_KILLSWITCH >/dev/null 2>&1; then
    iptables -I VERITASVPN_KILLSWITCH 1 -d "$ip" -p tcp --dport 443 -m comment --comment veritas-recovery -j ACCEPT >/dev/null 2>&1 || true
  fi
}

recovery_state_file() {
  local dir=""
  dir="$(recovery_state_dir)" || return 1
  printf '%s/recover.state' "$dir"
}

write_recover_state() {
  local file="" tmp="" token="${8:-}"
  [[ -z "$token" || "$token" =~ ^[0-9a-f]{16,64}$ ]] || return 1
  file="$(recovery_state_file)" || return 1
  tmp="$(mktemp)"
  chmod 600 "$tmp" 2>/dev/null || true
  printf 'pin_action=%s\napi_ip=%s\nvia=%s\ndev=%s\nold_via=%s\nold_dev=%s\nold_src=%s\ntoken=%s\n' \
    "$1" "$2" "$3" "$4" "$5" "$6" "$7" "$token" > "$tmp"
  mv "$tmp" "$file" || { rm -f "$tmp"; return 1; }
  chmod 600 "$file" 2>/dev/null || true
}

recovery_state_value() {
  local key="$1" file=""
  file="$(recovery_state_file 2>/dev/null || true)"
  [[ -n "$file" && -f "$file" && ! -L "$file" ]] || return 0
  awk -F= -v wanted="$key" '$1 == wanted { print substr($0, index($0, "=") + 1); exit }' "$file" 2>/dev/null || true
}

recovery_mark_pin_kept() {
  local pin="" api_ip="" via="" dev="" old_via="" old_dev="" old_src="" token=""
  pin="$(recovery_state_value pin_action)"
  [[ "$pin" == added || "$pin" == replaced ]] || return 0
  api_ip="$(recovery_state_value api_ip)"
  [[ "$api_ip" == "${CHOOSE_IP:-}" ]] || return 0
  via="$(recovery_state_value via)"
  dev="$(recovery_state_value dev)"
  old_via="$(recovery_state_value old_via)"
  old_dev="$(recovery_state_value old_dev)"
  old_src="$(recovery_state_value old_src)"
  token="$(recovery_state_value token)"
  write_recover_state "kept" "$api_ip" "$via" "$dev" "$old_via" "$old_dev" "$old_src" "$token" || true
}

recovery_undo_pin() {
  local pin="$1" api_ip="$2" via="$3" dev="$4" old_via="$5" old_dev="$6" old_src="$7"
  recovery_is_ipv4 "$api_ip" || return 0
  recovery_is_ipv4 "$via" || via=""
  recovery_valid_iface "$dev" || dev=""
  recovery_is_ipv4 "$old_via" || old_via=""
  recovery_valid_iface "$old_dev" || old_dev=""
  recovery_is_ipv4 "$old_src" || old_src=""
  if [[ "$pin" == added && -n "$via" && -n "$dev" ]]; then
    ip route del "$api_ip/32" via "$via" dev "$dev" >/dev/null 2>&1 || true
  elif [[ "$pin" == replaced ]]; then
    ip route replace "$api_ip/32" ${old_via:+via "$old_via"} ${old_dev:+dev "$old_dev"} ${old_src:+src "$old_src"} >/dev/null 2>&1 || true
  fi
}

recovery_close_body() {
  local pin="" api_ip="" via="" dev="" old_via="" old_dev="" old_src="" file=""
  pin="$(recovery_state_value pin_action)"
  api_ip="$(recovery_state_value api_ip)"
  via="$(recovery_state_value via)"
  dev="$(recovery_state_value dev)"
  old_via="$(recovery_state_value old_via)"
  old_dev="$(recovery_state_value old_dev)"
  old_src="$(recovery_state_value old_src)"
  delete_comment_rules "veritas-recovery"
  recovery_undo_pin "$pin" "$api_ip" "$via" "$dev" "$old_via" "$old_dev" "$old_src"
  file="$(recovery_state_file 2>/dev/null || true)"
  [[ -n "$file" ]] && rm -f "$file"
}

recovery_new_token() {
  local token=""
  if command -v od >/dev/null 2>&1; then
    token="$(od -An -N8 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n' || true)"
  fi
  if [[ ! "$token" =~ ^[0-9a-f]{16}$ ]] && command -v python3 >/dev/null 2>&1; then
    token="$(python3 -c 'import os; print(os.urandom(8).hex())' 2>/dev/null || true)"
  fi
  [[ "$token" =~ ^[0-9a-f]{16,64}$ ]] || return 1
  printf '%s' "$token"
}

schedule_recovery_expiry() {
  local uid="$1" token="$2" unit=""
  [[ "$uid" =~ ^[0-9]+$ ]] || return 1
  [[ "$token" =~ ^[0-9a-f]{16,64}$ ]] || return 1
  unit="veritasvpn-recover-$token"
  if command -v systemd-run >/dev/null 2>&1; then
    if systemd-run --quiet --collect --unit="$unit" --on-active=60s "$0" expire "$uid" "$token" >/dev/null 2>&1; then
      return 0
    fi
    if systemd-run --quiet --unit="$unit" --on-active=60s "$0" expire "$uid" "$token" >/dev/null 2>&1; then
      return 0
    fi
  fi
  command -v setsid >/dev/null 2>&1 || return 1
  setsid nohup "$0" expire-after "$uid" "$token" >/dev/null 2>&1 &
  [[ "$!" =~ ^[0-9]+$ ]]
}

recovery_open() {
  local api_ip="" endpoint_ip="" gw="" gif="" old_via="" old_dev="" old_src="" pin="none"
  local token="" uid=""
  # A previous exception must not outlive this open. Close it before the new pin.
  recovery_close_body
  api_ip="$(recovery_meta_value api_ip)"
  endpoint_ip="$(recovery_meta_value endpoint_ip)"
  recovery_is_ipv4 "$api_ip" || return 1
  read -r gw gif < <(detect_underlay_pair || true)
  recovery_is_ipv4 "$gw" || return 1
  recovery_valid_iface "$gif" || return 1
  uid="$(recovery_uid)" || return 1
  token="$(recovery_new_token)" || return 1
  if [[ "$api_ip" != "$endpoint_ip" ]]; then
    read -r old_via old_dev old_src < <(ip -4 route show "$api_ip/32" 2>/dev/null | awk 'NR==1 {
      via=""; dev=""; src=""
      for (i = 1; i <= NF; i++) {
        if ($i == "via") via=$(i+1)
        else if ($i == "dev") dev=$(i+1)
        else if ($i == "src") src=$(i+1)
      }
      printf "%s %s %s\n", via, dev, src
      exit
    }')
    recovery_is_ipv4 "$old_via" || old_via=""
    recovery_valid_iface "$old_dev" || old_dev=""
    recovery_is_ipv4 "$old_src" || old_src=""
    if [[ -z "$old_dev" && -z "$old_via" ]]; then
      if ! ip route add "$api_ip/32" via "$gw" dev "$gif" >/dev/null 2>&1; then
        return 1
      fi
      pin="added"
    elif [[ "$old_via" == "$gw" && "$old_dev" == "$gif" ]]; then
      pin="kept"
    else
      if ! ip route replace "$api_ip/32" via "$gw" dev "$gif" >/dev/null 2>&1; then
        return 1
      fi
      pin="replaced"
    fi
  fi
  if ! write_recover_state "$pin" "$api_ip" "$gw" "$gif" "$old_via" "$old_dev" "$old_src" "$token"; then
    recovery_undo_pin "$pin" "$api_ip" "$gw" "$gif" "$old_via" "$old_dev" "$old_src"
    return 1
  fi
  insert_recovery_accept "$api_ip"
  if ! schedule_recovery_expiry "$uid" "$token"; then
    recovery_close_body
    return 1
  fi
  printf 'ok\n'
}

# An empty token closes whatever exception is current. A token closes only
# the exception that stored it, so a stale 60s timer cannot remove a newer one.
recovery_close() {
  local token="${1:-}" current=""
  if [[ -n "$token" ]]; then
    [[ "$token" =~ ^[0-9a-f]{16,64}$ ]] || { printf 'ok\n'; return 0; }
    current="$(recovery_state_value token)"
    if [[ "$token" != "$current" ]]; then
      printf 'ok\n'
      return 0
    fi
  fi
  recovery_close_body
  printf 'ok\n'
}

recovery_expire() {
  local uid="${1:-}" token="${2:-}"
  [[ "$uid" =~ ^[0-9]+$ ]] || { printf 'ok\n'; return 0; }
  [[ "$token" =~ ^[0-9a-f]{16,64}$ ]] || { printf 'ok\n'; return 0; }
  RECOVERY_UID_FORCE="$uid"
  recovery_close "$token"
}

recovery_expire_after() {
  sleep 60
  recovery_expire "$@"
}

recovery_cidr_ok() {
  [[ "$1" =~ ^[0-9A-Fa-f:.]+/[0-9]{1,3}$ ]]
}

# Prints one CIDR per line. A full-tunnel default becomes two halves so the
# blackhole default underneath stays in place. The interface address is not a route.
recovery_route_lines() {
  local address="$1" allowed="$2" token=""
  : "$address"
  local IFS=','
  for token in $allowed; do
    token="${token#"${token%%[![:space:]]*}"}"
    token="${token%"${token##*[![:space:]]}"}"
    [[ -n "$token" ]] || continue
    recovery_cidr_ok "$token" || continue
    if [[ "$token" == "0.0.0.0/0" ]]; then
      printf '%s\n' "0.0.0.0/1" "128.0.0.0/1"
    elif [[ "$token" == "::/0" ]]; then
      printf '%s\n' "::/1" "8000::/1"
    else
      printf '%s\n' "$token"
    fi
  done
}

recovery_install_routes() {
  local address="$1" allowed="$2" cidr=""
  while IFS= read -r cidr; do
    [[ -n "$cidr" ]] || continue
    recovery_cidr_ok "$cidr" || continue
    if [[ "$cidr" == *:* ]]; then
      ip -6 route replace "$cidr" dev veritas0 >/dev/null 2>&1 || true
    else
      ip route replace "$cidr" dev veritas0 >/dev/null 2>&1 || true
    fi
  done < <(recovery_route_lines "$address" "$allowed")
}

recovery_update_meta() {
  local dir="" meta="" tmp="" bound="" dns="${1:-}"
  dir="$(recovery_state_dir)" || return 0
  meta="$dir/iface.meta"
  [[ -f "$meta" && ! -L "$meta" ]] || return 0
  bound="${STEALTH_BOUND_NOW:-}"
  [[ "$dns" =~ ^[0-9A-Fa-f:.]+$ ]] || dns=""
  tmp="$(mktemp)"
  awk -v epip="${CHOOSE_IP:-}" -v ep="${CHOOSE_EP:-}" -v stealth="${STEALTH_REMOTE:-}" -v prefix="${STEALTH_PREFIX:-}" -v bound="$bound" -v dns="$dns" '
    function emit(k, v) { print k "=" v }
    BEGIN { epip_set=0; ep_set=0; st_set=0; px_set=0; bd_set=0; dns_set=0 }
    /^endpoint_ip=/ { emit("endpoint_ip", epip); epip_set=1; next }
    /^endpoint=/ { emit("endpoint", ep); ep_set=1; next }
    /^stealth_remote=/ { emit("stealth_remote", stealth); st_set=1; next }
    /^stealth_prefix=/ { emit("stealth_prefix", prefix); px_set=1; next }
    /^stealth_bound=/ { emit("stealth_bound", bound); bd_set=1; next }
    /^dns=/ { if (dns != "") { emit("dns", dns); dns_set=1; next } print; next }
    { print }
    END {
      if (!epip_set) emit("endpoint_ip", epip)
      if (!ep_set) emit("endpoint", ep)
      if (!st_set) emit("stealth_remote", stealth)
      if (!px_set) emit("stealth_prefix", prefix)
      if (!bd_set && bound != "") emit("stealth_bound", bound)
      if (!dns_set && dns != "") emit("dns", dns)
    }
  ' "$meta" > "$tmp"
  mv "$tmp" "$meta"
}

recovery_apply() {
  local home="" conf="" address="" endpoint="" endpoint_wan="" endpoint_lan=""
  local stealth="" prefix="" dns="" fresh_ep="" fresh_stealth=""
  local gw="" gif="" src="" dir="" old_ip="" peer="" port="" now=""
  home="$(recovery_home)" || return 1
  conf="$home/.veritasvpn/wg.conf"
  [[ -f "$conf" && ! -L "$conf" ]] || return 1
  address="$(recovery_request_value address)"
  endpoint="$(recovery_request_value endpoint)"
  endpoint_wan="$(recovery_request_value endpoint_wan)"
  endpoint_lan="$(recovery_request_value endpoint_lan)"
  stealth="$(recovery_request_value stealth_remote)"
  prefix="$(recovery_request_value stealth_prefix)"
  dns="$(recovery_request_value dns)"
  fresh_ep="$(recovery_request_value fresh_ep)"
  fresh_stealth="$(recovery_request_value fresh_stealth)"
  [[ "$address" =~ ^[0-9A-Fa-f:.]+/[0-9]{1,3}$ ]] || return 1
  recovery_valid_ep "$endpoint" || return 1
  [[ -z "$endpoint_wan" ]] || recovery_valid_ep "$endpoint_wan" || return 1
  [[ -z "$endpoint_lan" ]] || recovery_valid_ep "$endpoint_lan" || return 1
  [[ -z "$stealth" ]] || recovery_valid_ep "$stealth" || return 1
  if [[ -n "$stealth" ]]; then
    [[ "$prefix" == /* && "$prefix" != *..* && "$prefix" =~ ^[A-Za-z0-9/._~-]+$ ]] || return 1
  else
    prefix=""
  fi
  [[ "$dns" =~ ^[0-9A-Fa-f:.]+$ ]] || return 1
  [[ -z "$fresh_ep" ]] || recovery_valid_ep "$fresh_ep" || fresh_ep=""
  [[ -z "$fresh_stealth" ]] || recovery_valid_ep "$fresh_stealth" || fresh_stealth=""

  dir="$(recovery_state_dir)" || return 1
  STATE_DIR="$dir"
  clear_fresh_state
  read -r gw gif < <(detect_underlay_pair || true)
  recovery_is_ipv4 "$gw" || return 1
  recovery_valid_iface "$gif" || return 1
  src="$(ip -4 -o addr show dev "$gif" 2>/dev/null | awk '{ print $4; exit }')"
  src="${src%%/*}"
  recovery_is_ipv4 "$src" || src=""
  NEW_GW="$gw"
  NEW_GW_IF="$gif"
  NEW_SRC="$src"
  ENDPOINT="$endpoint"
  ENDPOINT_WAN="$endpoint_wan"
  ENDPOINT_LAN="$endpoint_lan"
  FRESH_EP="$fresh_ep"
  FRESH_STEALTH="$fresh_stealth"
  STEALTH_REMOTE="$stealth"
  STEALTH_PREFIX="$prefix"
  WSTUNNEL_BIN="$(recovery_meta_value wstunnel_bin)"
  case "$WSTUNNEL_BIN" in
    /*) ;;
    *) WSTUNNEL_BIN="" ;;
  esac
  case "$WSTUNNEL_BIN" in
    *..*|*[!A-Za-z0-9/._~+-]*) WSTUNNEL_BIN="" ;;
  esac

  apply_endpoint_choice
  recovery_is_ipv4 "${CHOOSE_IP:-}" || return 1

  if ! ip link show veritas0 >/dev/null 2>&1; then
    ip link add veritas0 type wireguard >/dev/null 2>&1 || return 1
  fi
  ip addr replace "$address" dev veritas0 >/dev/null 2>&1 || return 1
  ip link set veritas0 up >/dev/null 2>&1 || return 1
  ip link set veritas0 mtu 1280 >/dev/null 2>&1 || true
  command -v wg >/dev/null 2>&1 || return 1
  wg setconf veritas0 "$conf" >/dev/null 2>&1 || return 1
  if [[ -z "${STEALTH_DIAL:-}" && "${CHOOSE_EP:-}" != 127.0.0.1:* ]]; then
    peer="$(wg show veritas0 peers 2>/dev/null | head -1 || true)"
    if [[ -n "$peer" ]]; then
      wg set veritas0 peer "$peer" endpoint "$CHOOSE_EP" >/dev/null 2>&1 || true
    fi
  fi
  recovery_install_routes "$address" "$(recovery_request_value allowed)"
  old_gif="$(recovery_meta_value gw_if)"
  if [[ -n "$old_gif" && "$old_gif" != "$gif" ]] && dns_link_name_ok "$old_gif"; then
    resolvectl default-route "$old_gif" true >/dev/null 2>&1 || true
  fi
  if ! apply_tunnel_dns "$dns" "$gif" "$STATE_DIR/iface.meta.links"; then
    if [[ -w /etc/resolv.conf ]]; then
      echo "nameserver $dns" > /etc/resolv.conf
    fi
  fi

  old_ip="$(recovery_meta_value endpoint_ip)"
  if recovery_is_ipv4 "$old_ip" && [[ "$old_ip" != "$CHOOSE_IP" && "$old_ip" != "127.0.0.1" ]]; then
    ip route del "$old_ip/32" >/dev/null 2>&1 || true
    ip route del "$old_ip" >/dev/null 2>&1 || true
  fi
  if [[ -n "$NEW_SRC" ]] && [[ "$(same_ipv4_24 "$CHOOSE_IP" "$NEW_SRC")" == yes ]]; then
    ip route replace "$CHOOSE_IP/32" dev "$NEW_GW_IF" src "$NEW_SRC" >/dev/null 2>&1 || \
      ip route replace "$CHOOSE_IP" dev "$NEW_GW_IF" >/dev/null 2>&1 || true
  else
    ip route replace "$CHOOSE_IP/32" via "$NEW_GW" dev "$NEW_GW_IF" >/dev/null 2>&1 || \
      ip route replace "$CHOOSE_IP" via "$NEW_GW" dev "$NEW_GW_IF" >/dev/null 2>&1 || true
  fi

  if [[ -n "${STEALTH_DIAL:-}" ]]; then
    port="$(port_of "$STEALTH_DIAL" 2>/dev/null || true)"
    [[ -n "$port" ]] && allow_outside_ip "$CHOOSE_IP" "$port" tcp
    STEALTH_BOUND_NOW="${NEW_GW}|${NEW_GW_IF}"
    start_wstunnel_pinned || return 1
    now="$(date +%s 2>/dev/null || echo 0)"
    [[ "$now" =~ ^[0-9]+$ ]] || now=0
    save_stealth_state "${NEW_GW}|${NEW_GW_IF}" "$now" 0 1
  else
    stop_wstunnel_pid
    port="$(port_of "${CHOOSE_EP:-}" 2>/dev/null || true)"
    [[ -n "$port" ]] && allow_outside_ip "$CHOOSE_IP" "$port" udp
    STEALTH_BOUND_NOW=""
    STEALTH_REMOTE=""
    rm -f "$STATE_DIR/wstunnel.pid"
  fi

  recovery_update_meta "$dns"
  recovery_mark_pin_kept
  clear_fresh_state
  start_handshake_publisher "$STATE_DIR" || true
  recovery_close >/dev/null
  printf 'ok\n'
}

recovery_main() {
  case "${1:-}" in
    open) recovery_open ;;
    close) recovery_close "${2:-}" ;;
    expire) recovery_expire "${2:-}" "${3:-}" ;;
    expire-after) recovery_expire_after "${2:-}" "${3:-}" ;;
    apply)
      if ! recovery_apply; then
        if dir="$(recovery_state_dir 2>/dev/null || true)" && [[ -n "$dir" ]]; then
          STATE_DIR="$dir"
          clear_fresh_state
        fi
        recovery_close >/dev/null || true
        return 1
      fi
      ;;
    *) return 1 ;;
  esac
}
