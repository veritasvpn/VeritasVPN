# Read the current peer on the underlay, outside the tunnel.
# Prints three lines: HTTP status, server_endpoint, stealth_endpoint.
# Status 0 is a timeout or network failure and is not "peer gone".
# The body and the access token are never printed.

detect_underlay_pair() {
  local gw="" gif=""
  gw="$(ip -4 route show default 2>/dev/null | awk -v iface="veritas0" '
    /blackhole/ { next }
    /via/ {
      for (i = 1; i <= NF; i++) if ($i == "dev") { d = $(i + 1); break }
      if (d == "" || d != iface) { print $3; exit }
    }')"
  gif="$(ip -4 route show default 2>/dev/null | awk -v iface="veritas0" '
    /blackhole/ { next }
    /via/ {
      for (i = 1; i <= NF; i++) if ($i == "dev") { d = $(i + 1); break }
      if (d == "" || d != iface) { print d; exit }
    }')"
  [[ "$gw" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || gw=""
  [[ "$gif" =~ ^[A-Za-z0-9_.:-]{1,15}$ ]] || gif=""
  printf '%s %s\n' "$gw" "$gif"
}

read_fresh_lines() {
  local gw="${1:-}" gif="${2:-}" home="" uid="" meta="" api_ip="" peer="" token="" endpoint_ip=""
  [[ -n "${SUDO_USER:-}" && "$SUDO_USER" != "root" ]] || { printf '0\n\n\n'; return 0; }
  home="$(getent passwd "$SUDO_USER" 2>/dev/null | cut -d: -f6)"
  [[ "$home" == /* && "$home" != *..* ]] || { printf '0\n\n\n'; return 0; }
  uid="$(id -u "$SUDO_USER" 2>/dev/null || true)"
  [[ "$uid" =~ ^[0-9]+$ ]] || { printf '0\n\n\n'; return 0; }
  meta="/var/lib/veritasvpn/state/${uid}/iface.meta"
  if [[ -f "$meta" && ! -L "$meta" ]]; then
    api_ip="$(awk -F= '$1=="api_ip"{print substr($0, index($0, "=") + 1); exit}' "$meta" 2>/dev/null || true)"
    endpoint_ip="$(awk -F= '$1=="endpoint_ip"{print substr($0, index($0, "=") + 1); exit}' "$meta" 2>/dev/null || true)"
  fi
  peer="$(tr -cd 'A-Za-z0-9_-' < "$home/.veritasvpn/peer_id" 2>/dev/null || true)"
  token="$(tr -cd 'A-Za-z0-9._-' < "$home/.veritasvpn/peer-check.token" 2>/dev/null || true)"
  if [[ -z "$gw" || -z "$gif" ]]; then
    read -r gw gif < <(detect_underlay_pair || true)
  fi
  [[ "$api_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { printf '0\n\n\n'; return 0; }
  [[ "$gif" =~ ^[A-Za-z0-9_.:-]{1,15}$ ]] || { printf '0\n\n\n'; return 0; }
  [[ "$gw" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || { printf '0\n\n\n'; return 0; }
  [[ "$peer" =~ ^[A-Za-z0-9_-]{1,128}$ ]] || { printf '0\n\n\n'; return 0; }
  [[ -n "$token" && ${#token} -le 4096 ]] || { printf '0\n\n\n'; return 0; }

  (
    local pin=0 nft_handle="" ipt=0 body="" cfg="" code="0" parsed="" ep="" st=""
    cleanup() {
      if [[ -n "$nft_handle" ]] && [[ "$nft_handle" =~ ^[0-9]+$ ]]; then
        nft delete rule inet veritasvpn_killswitch output handle "$nft_handle" >/dev/null 2>&1 || true
      fi
      if [[ "$ipt" == 1 ]]; then
        iptables -D VERITASVPN_KILLSWITCH -d "$api_ip" -p tcp --dport 443 -j ACCEPT >/dev/null 2>&1 || true
      fi
      if [[ "$pin" == 1 ]]; then
        ip route del "$api_ip/32" >/dev/null 2>&1 || true
      fi
      [[ -n "$body" ]] && rm -f "$body"
      [[ -n "$cfg" ]] && rm -f "$cfg"
    }
    trap cleanup EXIT
    body="$(mktemp)"
    cfg="$(mktemp)"
    chmod 600 "$body" "$cfg" 2>/dev/null || true
    if [[ "$api_ip" != "$endpoint_ip" ]]; then
      if ip route replace "$api_ip/32" via "$gw" dev "$gif" >/dev/null 2>&1; then
        pin=1
      fi
    fi
    if command -v nft >/dev/null 2>&1 && nft list table inet veritasvpn_killswitch >/dev/null 2>&1; then
      nft insert rule inet veritasvpn_killswitch output ip daddr "$api_ip" tcp dport 443 accept >/dev/null 2>&1 || true
      nft_handle="$(nft -a list chain inet veritasvpn_killswitch output 2>/dev/null | awk -v ip="$api_ip" '
        index($0, "ip daddr " ip " ") && index($0, "tcp dport 443") && index($0, "accept") {
          for (i = 1; i <= NF; i++) if ($i == "handle") { print $(i + 1); exit }
        }')"
      [[ "$nft_handle" =~ ^[0-9]+$ ]] || nft_handle=""
    fi
    if command -v iptables >/dev/null 2>&1 && iptables -L VERITASVPN_KILLSWITCH >/dev/null 2>&1; then
      if ! iptables -C VERITASVPN_KILLSWITCH -d "$api_ip" -p tcp --dport 443 -j ACCEPT >/dev/null 2>&1; then
        if iptables -I VERITASVPN_KILLSWITCH 1 -d "$api_ip" -p tcp --dport 443 -j ACCEPT >/dev/null 2>&1; then
          ipt=1
        fi
      fi
    fi
    printf 'header = "Authorization: Bearer %s"\n' "$token" > "$cfg"
    printf 'resolve = "api.veritasvpn.cloud:443:%s"\n' "$api_ip" >> "$cfg"
    printf 'interface = "%s"\n' "$gif" >> "$cfg"
    printf 'silent\n' >> "$cfg"
    code="$(curl --config "$cfg" -4 --connect-timeout 2 --max-time 4 -o "$body" -w '%{http_code}' \
      "https://api.veritasvpn.cloud/api/v1/wg/peers/${peer}" 2>/dev/null || true)"
    [[ "$code" =~ ^[0-9]{3}$ ]] || code=0
    if [[ "$code" == 200 ]] && command -v python3 >/dev/null 2>&1; then
      parsed="$(python3 -c '
import json, re, sys
ep_re = re.compile(r"^[A-Za-z0-9.-]+:[0-9]{1,5}$")
try:
    data = json.load(open(sys.argv[1]))
except Exception:
    print()
    print()
    raise SystemExit
def one(key):
    val = data.get(key) or ""
    if not isinstance(val, str):
        return ""
    val = val.strip()
    return val if ep_re.match(val) else ""
print(one("server_endpoint"))
print(one("stealth_endpoint"))
' "$body" 2>/dev/null || true)"
      ep="$(printf '%s\n' "$parsed" | sed -n '1p')"
      st="$(printf '%s\n' "$parsed" | sed -n '2p')"
    fi
    cleanup
    trap - EXIT
    printf '%s\n%s\n%s\n' "$code" "$ep" "$st"
  )
}

peer_check_main() {
  local gw="" gif="" lines="" status=""
  [[ -n "${SUDO_USER:-}" && "$SUDO_USER" != "root" ]] || { printf '0\n'; return 0; }
  read -r gw gif < <(detect_underlay_pair || true)
  lines="$(read_fresh_lines "$gw" "$gif" || true)"
  status="$(printf '%s\n' "$lines" | sed -n '1p')"
  [[ "$status" =~ ^[0-9]+$ ]] || status=0
  printf '%s\n' "$status"
}
