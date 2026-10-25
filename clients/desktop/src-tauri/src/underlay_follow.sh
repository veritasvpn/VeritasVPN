# Move an existing endpoint host route onto the underlay the OS just chose.
# Called before any peer read so a slow read cannot leave the kill switch
# pinned to an interface that has already gone away.
# Returns 1 when the new gateway or interface is not ready yet.
follow_underlay_route() {
  local endpoint_ip="${1:-}" new_gw="${2:-}" new_if="${3:-}" new_src="${4:-}"
  [[ -n "$new_gw" && -n "$new_if" ]] || return 1
  [[ "$new_gw" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
  [[ "$new_if" =~ ^[A-Za-z0-9_.:-]{1,15}$ ]] || return 1
  # Stealth keeps WireGuard on loopback. The remote host route is separate.
  if [[ ! "$endpoint_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ || "$endpoint_ip" == "127.0.0.1" ]]; then
    return 0
  fi
  ip route del "$endpoint_ip" 2>/dev/null || true
  ip route del "$endpoint_ip/32" 2>/dev/null || true
  if [[ -n "$new_src" ]] && [[ "$(same_ipv4_24 "$endpoint_ip" "$new_src")" == "yes" ]]; then
    ip route replace "$endpoint_ip/32" dev "$new_if" src "$new_src" 2>/dev/null || \
      ip route replace "$endpoint_ip" dev "$new_if" 2>/dev/null || true
  else
    ip route replace "$endpoint_ip/32" via "$new_gw" dev "$new_if" 2>/dev/null || \
      ip route replace "$endpoint_ip" via "$new_gw" 2>/dev/null || true
  fi
  return 0
}
