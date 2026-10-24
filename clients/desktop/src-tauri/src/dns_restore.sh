# Undo VeritasVPN DNS changes on the links this app touched.
# systemd-resolved sends a link's DNS out that link, so the tunnel owns global
# lookups while connected and the physical NIC is restored on the way down.
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

apply_tunnel_dns() {
  local dns="$1" underlay="$2" links_file="$3"
  [[ "$dns" =~ ^[0-9A-Fa-f:.]+$ ]] || return 1
  command -v resolvectl >/dev/null 2>&1 || return 1
  # Scope global lookups to the tunnel. Leave the physical link's server list
  # alone so a later revert still has the network's own resolvers.
  resolvectl dns veritas0 "$dns" >/dev/null 2>&1 || return 1
  resolvectl domain veritas0 '~.' >/dev/null 2>&1 || true
  resolvectl default-route veritas0 true >/dev/null 2>&1 || true
  if dns_link_name_ok "$underlay"; then
    resolvectl default-route "$underlay" false >/dev/null 2>&1 || true
    remember_dns_link "$links_file" "$underlay"
  fi
  resolvectl flush-caches >/dev/null 2>&1 || true
  return 0
}

link_dns_stuck() {
  local link="$1" status=""
  dns_link_name_ok "$link" || return 1
  command -v resolvectl >/dev/null 2>&1 || return 1
  ip -4 -o addr show dev "$link" 2>/dev/null | grep -q 'inet ' || return 1
  status="$(resolvectl status "$link" 2>/dev/null || true)"
  [[ -n "$status" ]] || return 1
  if printf '%s\n' "$status" | grep -q -- '-DefaultRoute'; then
    return 0
  fi
  if ! printf '%s\n' "$status" | grep -Eq '^[[:space:]]*(Current DNS Server:|DNS Servers:).*[0-9A-Fa-f]'; then
    return 0
  fi
  return 1
}

restore_link_dns() {
  local link="$1"
  dns_link_name_ok "$link" || return 0
  if command -v resolvectl >/dev/null 2>&1; then
    resolvectl revert "$link" >/dev/null 2>&1 || true
  fi
  if link_dns_stuck "$link" && command -v nmcli >/dev/null 2>&1; then
    nmcli device reapply "$link" >/dev/null 2>&1 || true
  fi
}

physical_links() {
  local entry="" name=""
  for entry in /sys/class/net/*; do
    name="${entry##*/}"
    dns_link_name_ok "$name" || continue
    [[ -e "$entry/device" || -d "$entry/wireless" ]] || continue
    ip -4 -o addr show dev "$name" 2>/dev/null | grep -q 'inet ' || continue
    printf '%s\n' "$name"
  done
}

restore_veritas_dns() {
  local links_file="$1" backup="$2" meta="$3" extra="${4:-}" link="" stuck=0
  if [[ -f "$links_file" && ! -L "$links_file" ]]; then
    while IFS= read -r link; do
      restore_link_dns "$link" || true
    done < "$links_file"
  fi
  if [[ -n "$extra" ]]; then
    restore_link_dns "$extra" || true
  fi
  if command -v resolvectl >/dev/null 2>&1; then
    resolvectl revert veritas0 >/dev/null 2>&1 || true
  fi
  while IFS= read -r link; do
    if link_dns_stuck "$link"; then
      restore_link_dns "$link" || true
    fi
  done < <(physical_links 2>/dev/null || true)
  if [[ -f "$backup" && ! -L "$backup" ]]; then
    cat "$backup" > /etc/resolv.conf 2>/dev/null || true
  fi
  if command -v resolvectl >/dev/null 2>&1; then
    resolvectl flush-caches >/dev/null 2>&1 || true
  fi
  stuck=0
  while IFS= read -r link; do
    if link_dns_stuck "$link"; then
      stuck=1
    fi
  done < <(physical_links 2>/dev/null || true)
  if [[ "$stuck" -eq 1 ]] && command -v systemctl >/dev/null 2>&1; then
    systemctl reload NetworkManager >/dev/null 2>&1 || true
    while IFS= read -r link; do
      if link_dns_stuck "$link"; then
        restore_link_dns "$link" || true
      fi
    done < <(physical_links 2>/dev/null || true)
    stuck=0
    while IFS= read -r link; do
      if link_dns_stuck "$link"; then
        stuck=1
      fi
    done < <(physical_links 2>/dev/null || true)
  fi
  if [[ "$stuck" -eq 0 ]]; then
    rm -f "$backup" "$meta" "$links_file" 2>/dev/null || true
  fi
}
