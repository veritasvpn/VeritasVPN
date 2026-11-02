# Undo VeritasVPN DNS changes on the links this app recorded.
# While connected, global lookups are scoped to veritas0 and the underlay's
# default DNS route is turned off. Disconnect puts that route back.
# A physical NIC that was never recorded is left alone, including one that
# has no DNS servers or no default route of its own.
# Tailscale and other virtual interfaces are never passed to resolvectl.

dns_link_name_ok() {
  local name="${1:-}"
  [[ "$name" =~ ^[A-Za-z0-9_.:-]{1,15}$ ]] || return 1
  case "$name" in
    lo|veritas0|tailscale0|docker0|tun0|wg0) return 1 ;;
    tailscale*|docker*|veth*|br-*|virbr*|tun*|wg*|zt*|ifb*) return 1 ;;
  esac
  return 0
}

remember_dns_link() {
  local file="$1" link="$2"
  dns_link_name_ok "$link" || return 0
  if [[ -f "$file" && ! -L "$file" ]] && grep -qx -- "$link" "$file" 2>/dev/null; then
    return 0
  fi
  printf '%s\n' "$link" >> "$file" 2>/dev/null || true
}

# The links file is iface.meta.links. Record gw_if in iface.meta before the
# underlay route changes, without dropping the other fields already there.
remember_gw_if() {
  local links_file="$1" link="$2" meta="" tmp=""
  dns_link_name_ok "$link" || return 0
  case "$links_file" in
    *.links) meta="${links_file%.links}" ;;
    *) return 0 ;;
  esac
  [[ -L "$meta" ]] && return 0
  if [[ -f "$meta" ]]; then
    tmp="$(mktemp "${meta}.XXXXXX" 2>/dev/null || true)"
    [[ -n "$tmp" ]] || return 0
    awk -v gif="$link" '
      BEGIN { done = 0 }
      /^gw_if=/ { print "gw_if=" gif; done = 1; next }
      { print }
      END { if (!done) print "gw_if=" gif }
    ' "$meta" > "$tmp" 2>/dev/null || { rm -f "$tmp"; return 0; }
    mv -f "$tmp" "$meta" 2>/dev/null || rm -f "$tmp"
  else
    printf 'gw_if=%s\n' "$link" > "$meta" 2>/dev/null || true
  fi
}

dns_run() {
  local seconds="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout "$seconds" "$@" >/dev/null 2>&1 || true
  else
    "$@" >/dev/null 2>&1 || true
  fi
}

apply_tunnel_dns() {
  local dns="$1" underlay="$2" links_file="$3"
  [[ "$dns" =~ ^[0-9A-Fa-f:.]+$ ]] || return 1
  command -v resolvectl >/dev/null 2>&1 || return 1
  # Scope global lookups to the tunnel. Leave the physical link's server list
  # alone. Disconnect only turns its default DNS route back on.
  if command -v timeout >/dev/null 2>&1; then
    timeout 5 resolvectl dns veritas0 "$dns" >/dev/null 2>&1 || return 1
    timeout 5 resolvectl domain veritas0 '~.' >/dev/null 2>&1 || true
    timeout 5 resolvectl default-route veritas0 true >/dev/null 2>&1 || true
  else
    resolvectl dns veritas0 "$dns" >/dev/null 2>&1 || return 1
    resolvectl domain veritas0 '~.' >/dev/null 2>&1 || true
    resolvectl default-route veritas0 true >/dev/null 2>&1 || true
  fi
  if dns_link_name_ok "$underlay"; then
    # Record the link before turning its default DNS route off. A crash
    # between these steps must still leave a name to restore.
    remember_dns_link "$links_file" "$underlay"
    remember_gw_if "$links_file" "$underlay"
    dns_run 5 resolvectl default-route "$underlay" false
  fi
  dns_run 5 resolvectl flush-caches
  return 0
}

link_default_route_off() {
  local link="$1" status=""
  dns_link_name_ok "$link" || return 1
  command -v resolvectl >/dev/null 2>&1 || return 1
  if command -v timeout >/dev/null 2>&1; then
    status="$(timeout 5 resolvectl status "$link" 2>/dev/null || true)"
  else
    status="$(resolvectl status "$link" 2>/dev/null || true)"
  fi
  [[ -n "$status" ]] || return 1
  printf '%s\n' "$status" | grep -q -- '-DefaultRoute'
}

restore_link_dns() {
  local link="$1"
  dns_link_name_ok "$link" || return 0
  # Restore only the flag this app cleared. A full revert would drop the
  # resolvers the network configured itself.
  dns_run 5 resolvectl default-route "$link" true
  if link_default_route_off "$link"; then
    dns_run 15 nmcli device reapply "$link"
  fi
}

restore_veritas_dns() {
  local links_file="$1" backup="$2" meta="$3" extra="${4:-}"
  local link="" stuck=0
  local -a links=()
  local -A seen=()
  if [[ -f "$links_file" && ! -L "$links_file" ]]; then
    while IFS= read -r link || [[ -n "$link" ]]; do
      dns_link_name_ok "$link" || continue
      [[ -n "${seen[$link]+x}" ]] && continue
      seen["$link"]=1
      links+=("$link")
    done < "$links_file"
  fi
  if dns_link_name_ok "$extra" && [[ -z "${seen[$extra]+x}" ]]; then
    links+=("$extra")
  fi
  # veritas0 is the tunnel this app created. Do not revert any other link.
  dns_run 5 resolvectl revert veritas0
  for link in "${links[@]+"${links[@]}"}"; do
    restore_link_dns "$link" || true
  done
  if [[ -f "$backup" && ! -L "$backup" && -w /etc/resolv.conf ]]; then
    cat "$backup" > /etc/resolv.conf 2>/dev/null || true
  fi
  dns_run 5 resolvectl flush-caches
  stuck=0
  for link in "${links[@]+"${links[@]}"}"; do
    if link_default_route_off "$link"; then
      stuck=1
    fi
  done
  if [[ "$stuck" -eq 1 ]]; then
    dns_run 20 systemctl reload NetworkManager
    for link in "${links[@]+"${links[@]}"}"; do
      if link_default_route_off "$link"; then
        restore_link_dns "$link" || true
      fi
    done
    stuck=0
    for link in "${links[@]+"${links[@]}"}"; do
      if link_default_route_off "$link"; then
        stuck=1
      fi
    done
  fi
  if [[ "$stuck" -eq 0 ]]; then
    rm -f "$backup" "$meta" "$links_file" 2>/dev/null || true
  fi
}
