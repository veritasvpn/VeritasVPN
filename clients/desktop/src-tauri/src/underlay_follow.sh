# Move an existing endpoint host route onto the underlay the OS just chose.
# Called before any peer read so a slow read cannot leave the kill switch
# pinned to an interface that has already gone away.
# Returns 1 when the new gateway or interface is not ready, or when the
# host route is not actually on that interface afterwards.

net_sys_path() {
  local dev="$1" leaf="$2"
  if [[ -n "${VERITAS_SYS_ROOT:-}" ]]; then
    printf '%s/class/net/%s/%s\n' "$VERITAS_SYS_ROOT" "$dev" "$leaf"
  else
    printf '/sys/class/net/%s/%s\n' "$dev" "$leaf"
  fi
}

sysctl_path() {
  local rel="$1"
  if [[ -n "${VERITAS_SYSCTL_ROOT:-}" ]]; then
    printf '%s/%s\n' "$VERITAS_SYSCTL_ROOT" "$rel"
  else
    printf '/proc/sys/%s\n' "$rel"
  fi
}

iface_name_ok() {
  [[ "${1:-}" =~ ^[A-Za-z0-9_.:-]{1,15}$ ]]
}

# Missing operstate counts as up so a route is not ignored on a minimal host.
# An explicit "down" is skipped so a dead Wi-Fi default does not beat a live NIC.
iface_oper_up() {
  local dev="$1" path="" state=""
  iface_name_ok "$dev" || return 1
  path="$(net_sys_path "$dev" operstate)"
  [[ -r "$path" ]] || return 0
  state="$(cat "$path" 2>/dev/null || true)"
  [[ "$state" != "down" ]]
}

# First unicast default that is not the tunnel and not a down interface.
# Prints "gateway iface". Returns 1 when nothing is ready.
pick_underlay() {
  local tunnel="${1:-veritas0}" line="" gw="" dev=""
  while IFS= read -r line; do
    [[ "$line" == *blackhole* ]] && continue
    [[ "$line" == *" via "* ]] || continue
    gw="$(awk '{ for (i = 1; i <= NF; i++) if ($i == "via") { print $(i + 1); exit } }' <<<"$line")"
    dev="$(awk '{ for (i = 1; i <= NF; i++) if ($i == "dev") { print $(i + 1); exit } }' <<<"$line")"
    [[ "$gw" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || continue
    iface_name_ok "$dev" || continue
    [[ "$dev" != "$tunnel" && "$dev" != "lo" ]] || continue
    iface_oper_up "$dev" || continue
    printf '%s %s\n' "$gw" "$dev"
    return 0
  done < <(ip -4 route show default 2>/dev/null || true)
  return 1
}

# True when `ip route get` sends this host via the underlay, not the tunnel.
endpoint_route_ok() {
  local endpoint_ip="${1:-}" iface="${2:-}" got="" dev=""
  [[ "$endpoint_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
  [[ "$endpoint_ip" == "127.0.0.1" ]] && return 0
  iface_name_ok "$iface" || return 1
  [[ "$iface" != "veritas0" ]] || return 1
  got="$(ip -4 route get "$endpoint_ip" 2>/dev/null | head -1 || true)"
  dev="$(awk '{ for (i = 1; i <= NF; i++) if ($i == "dev") { print $(i + 1); exit } }' <<<"$got")"
  [[ "$dev" == "$iface" ]] || return 1
  return 0
}

follow_underlay_route() {
  local endpoint_ip="${1:-}" new_gw="${2:-}" new_if="${3:-}" new_src="${4:-}"
  [[ -n "$new_gw" && -n "$new_if" ]] || return 1
  [[ "$new_gw" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
  iface_name_ok "$new_if" || return 1
  # Stealth keeps WireGuard on loopback. The remote host route is separate.
  if [[ ! "$endpoint_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ || "$endpoint_ip" == "127.0.0.1" ]]; then
    return 0
  fi
  # Install over the old route. Deleting first left no host route when replace
  # failed, and encrypted packets then fell into the tunnel's /1 routes.
  if [[ -n "$new_src" ]] && [[ "$(same_ipv4_24 "$endpoint_ip" "$new_src")" == "yes" ]]; then
    ip route replace "$endpoint_ip/32" dev "$new_if" src "$new_src" 2>/dev/null || \
      ip route replace "$endpoint_ip" dev "$new_if" src "$new_src" 2>/dev/null || \
      ip route replace "$endpoint_ip/32" dev "$new_if" 2>/dev/null || true
  else
    ip route replace "$endpoint_ip/32" via "$new_gw" dev "$new_if" 2>/dev/null || \
      ip route replace "$endpoint_ip" via "$new_gw" dev "$new_if" 2>/dev/null || true
  fi
  endpoint_route_ok "$endpoint_ip" "$new_if"
}

loosen_rp_filter() {
  local iface="$1" path=""
  iface_name_ok "$iface" || return 0
  # Per-interface only. The kernel uses max(all, iface), so 2 (loose) wins
  # even when "all" is strict. Do not change the global "all" knob.
  path="$(sysctl_path "net/ipv4/conf/${iface}/rp_filter")"
  if [[ -n "${VERITAS_SYSCTL_ROOT:-}" ]]; then
    mkdir -p "$(dirname "$path")" 2>/dev/null || true
  fi
  [[ -n "${VERITAS_SYSCTL_ROOT:-}" || -w "$path" ]] || return 0
  printf '2\n' > "$path" 2>/dev/null || true
}

accept_tunnel_input() {
  # Decrypted packets arrive IN on veritas0. After a re-path, conntrack can
  # mark the return traffic INVALID and UFW's default deny logs UFW BLOCK.
  # Accept only this interface. The OUTPUT kill switch is left as it is.
  command -v iptables >/dev/null 2>&1 || return 0
  if iptables -S ufw-before-input >/dev/null 2>&1; then
    iptables -C ufw-before-input -i veritas0 -m comment --comment veritas-tun-in -j ACCEPT >/dev/null 2>&1 || \
      iptables -I ufw-before-input 1 -i veritas0 -m comment --comment veritas-tun-in -j ACCEPT >/dev/null 2>&1 || true
  fi
  iptables -C INPUT -i veritas0 -m comment --comment veritas-tun-in -j ACCEPT >/dev/null 2>&1 || \
    iptables -I INPUT 1 -i veritas0 -m comment --comment veritas-tun-in -j ACCEPT >/dev/null 2>&1 || true
}

clear_tunnel_input() {
  local _i
  command -v iptables >/dev/null 2>&1 || return 0
  for _i in 1 2 3 4 5 6 7 8; do
    iptables -C ufw-before-input -i veritas0 -m comment --comment veritas-tun-in -j ACCEPT >/dev/null 2>&1 || break
    iptables -D ufw-before-input -i veritas0 -m comment --comment veritas-tun-in -j ACCEPT >/dev/null 2>&1 || break
  done
  for _i in 1 2 3 4 5 6 7 8; do
    iptables -C INPUT -i veritas0 -m comment --comment veritas-tun-in -j ACCEPT >/dev/null 2>&1 || break
    iptables -D INPUT -i veritas0 -m comment --comment veritas-tun-in -j ACCEPT >/dev/null 2>&1 || break
  done
}

forget_iface_conntrack() {
  local old="${1:-}"
  iface_name_ok "$old" || return 0
  [[ "$old" != "veritas0" ]] || return 0
  command -v conntrack >/dev/null 2>&1 || return 0
  if command -v timeout >/dev/null 2>&1; then
    timeout 3 conntrack -D -i "$old" >/dev/null 2>&1 || true
    timeout 3 conntrack -D -o "$old" >/dev/null 2>&1 || true
  else
    conntrack -D -i "$old" >/dev/null 2>&1 || true
    conntrack -D -o "$old" >/dev/null 2>&1 || true
  fi
}

prepare_repath() {
  local new_if="${1:-}" old_if="${2:-}"
  loosen_rp_filter veritas0
  if iface_name_ok "$new_if"; then
    loosen_rp_filter "$new_if"
  fi
  accept_tunnel_input
  if [[ -n "$old_if" && "$old_if" != "$new_if" ]]; then
    forget_iface_conntrack "$old_if"
  fi
}

# A path-adapt that outlives `timeout` must not reinstall routes after teardown.
stop_path_adapt() {
  local pid=""
  command -v pgrep >/dev/null 2>&1 || return 0
  while IFS= read -r pid; do
    [[ "$pid" =~ ^[0-9]+$ ]] || continue
    [[ "$pid" == "$$" ]] && continue
    kill -9 "$pid" 2>/dev/null || true
  done < <(pgrep -f '/var/lib/veritasvpn/path-adapt.sh' 2>/dev/null || true)
}
