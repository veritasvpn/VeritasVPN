#!/bin/bash
# Drop a VeritasVPN tunnel left behind by a package swap or a killed app.
# dpkg runs this as root (preinst and prerm). The desktop app may also run it
# through pkexec, with no arguments. Every step is idempotent. A missing tool
# or route is success. This never touches other vendors' policy rules or
# routing tables.

# Only a maintainer script may stop the desktop process. The app invokes this
# same file to clear a leftover firewall and must keep running.
stop_desktop_app() {
  local entry="" pid="" raw=""
  for entry in /proc/[0-9]*/exe; do
    pid="${entry#/proc/}"
    pid="${pid%/exe}"
    case "$pid" in
      ''|*[!0-9]*) continue ;;
    esac
    [ "$pid" = "$$" ] && continue
    raw="$(readlink "$entry" 2>/dev/null || true)"
    case "$raw" in
      */veritasvpn-desktop|*/veritasvpn-desktop' (deleted)') ;;
      *) continue ;;
    esac
    kill "$pid" 2>/dev/null || true
  done
  sleep 0.3
  for entry in /proc/[0-9]*/exe; do
    pid="${entry#/proc/}"
    pid="${pid%/exe}"
    case "$pid" in
      ''|*[!0-9]*) continue ;;
    esac
    raw="$(readlink "$entry" 2>/dev/null || true)"
    case "$raw" in
      */veritasvpn-desktop|*/veritasvpn-desktop' (deleted)') ;;
      *) continue ;;
    esac
    kill -9 "$pid" 2>/dev/null || true
  done
}

stop_pid_file() {
  local file="$1" expected="$2" pid="" uid="" command=""
  [ -f "$file" ] && [ ! -L "$file" ] || return 0
  pid="$(cat "$file" 2>/dev/null || true)"
  case "$pid" in
    ''|*[!0-9]*) return 0 ;;
  esac
  uid="$(ps -o uid= -p "$pid" 2>/dev/null | tr -d ' ' || true)"
  command="$(ps -o command= -p "$pid" 2>/dev/null || true)"
  case "$command" in
    *"$expected"*) ;;
    *) return 0 ;;
  esac
  [ "$uid" = "0" ] || return 0
  kill "$pid" 2>/dev/null || true
  sleep 0.2
  kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
  rm -f "$file" 2>/dev/null || true
}

restore_one_state() {
  local dir="$1"
  [ -d "$dir" ] && [ ! -L "$dir" ] || return 0
  stop_pid_file "$dir/handshake.pid" "/var/lib/veritasvpn/handshake.sh" || true
  stop_pid_file "$dir/wireguard-go.pid" "wireguard-go" || true
  stop_pid_file "$dir/wstunnel.pid" "wstunnel" || true
  rm -f "$dir/handshake" "$dir/handshake.pid" 2>/dev/null || true
}

case "${1:-}" in
  install|upgrade|remove|deconfigure|failed-upgrade|abort-install|abort-upgrade|abort-remove)
    stop_desktop_app || true
    ;;
esac

shopt -s nullglob
for dir in /var/lib/veritasvpn/state/[0-9]*; do
  restore_one_state "$dir" || true
done
shopt -u nullglob

ip route del 0.0.0.0/1 dev veritas0 2>/dev/null || true
ip route del 128.0.0.0/1 dev veritas0 2>/dev/null || true
ip route del 10.0.0.0/24 dev veritas0 2>/dev/null || true
ip route del blackhole default metric 1 2>/dev/null || true
ip -6 route del blackhole default metric 1 2>/dev/null || true
ip link set veritas0 down 2>/dev/null || true
ip link delete veritas0 2>/dev/null || true
rm -f /var/run/wireguard/veritas0.sock 2>/dev/null || true

if command -v nft >/dev/null 2>&1; then
  nft delete table inet veritasvpn_killswitch 2>/dev/null || true
fi
if command -v iptables >/dev/null 2>&1; then
  while iptables -C OUTPUT -j VERITASVPN_KILLSWITCH 2>/dev/null; do
    iptables -D OUTPUT -j VERITASVPN_KILLSWITCH 2>/dev/null || break
  done
  iptables -F VERITASVPN_KILLSWITCH 2>/dev/null || true
  iptables -X VERITASVPN_KILLSWITCH 2>/dev/null || true
fi
if command -v ip6tables >/dev/null 2>&1; then
  while ip6tables -C OUTPUT -j VERITASVPN_KILLSWITCH_V6 2>/dev/null; do
    ip6tables -D OUTPUT -j VERITASVPN_KILLSWITCH_V6 2>/dev/null || break
  done
  ip6tables -F VERITASVPN_KILLSWITCH_V6 2>/dev/null || true
  ip6tables -X VERITASVPN_KILLSWITCH_V6 2>/dev/null || true
fi

# BEGIN dns_restore.sh
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
# END dns_restore.sh

restore_saved_dns() {
  local dir="$1" meta="" backup="" links="" gw=""
  [ -d "$dir" ] && [ ! -L "$dir" ] || return 0
  meta="$dir/iface.meta"
  backup="${meta}.dns"
  links="${meta}.links"
  gw=""
  if [ -f "$meta" ] && [ ! -L "$meta" ]; then
    gw="$(awk -F= '$1 == "gw_if" { print substr($0, index($0, "=") + 1); exit }' "$meta" 2>/dev/null || true)"
  fi
  case "$gw" in
    [A-Za-z0-9_.:-][A-Za-z0-9_.:-]*) ;;
    *) gw="" ;;
  esac
  if [ "${#gw}" -gt 15 ]; then
    gw=""
  fi
  restore_veritas_dns "$links" "$backup" "$meta" "$gw" || true
}

shopt -s nullglob
for dir in /var/lib/veritasvpn/state/[0-9]*; do
  restore_saved_dns "$dir" || true
done
shopt -u nullglob

exit 0
