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
  local dir="$1" meta="" backup="" gw=""
  [ -d "$dir" ] && [ ! -L "$dir" ] || return 0
  stop_pid_file "$dir/handshake.pid" "/var/lib/veritasvpn/handshake.sh" || true
  stop_pid_file "$dir/wireguard-go.pid" "wireguard-go" || true
  stop_pid_file "$dir/wstunnel.pid" "wstunnel" || true
  meta="$dir/iface.meta"
  backup="${meta}.dns"
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
  if [ -f "$backup" ] && [ ! -L "$backup" ]; then
    cat "$backup" > /etc/resolv.conf 2>/dev/null || true
  fi
  if [ -n "$gw" ] && command -v resolvectl >/dev/null 2>&1; then
    resolvectl revert "$gw" 2>/dev/null || true
    resolvectl flush-caches 2>/dev/null || true
  fi
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

exit 0
