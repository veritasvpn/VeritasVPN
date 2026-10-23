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
    local pin="none" body="" cfg="" code="0" parsed="" ep="" st=""
    local old_via="" old_dev="" old_src=""
    delete_fresh_rules() {
      local handle="" line="" spec="" _i
      if command -v nft >/dev/null 2>&1 && nft list table inet veritasvpn_killswitch >/dev/null 2>&1; then
        for _i in 1 2 3 4 5 6 7 8; do
          handle="$(nft -a list chain inet veritasvpn_killswitch output 2>/dev/null | awk '
            index($0, "comment \"veritas-fresh\"") {
              for (i = 1; i <= NF; i++) if ($i == "handle") { print $(i + 1); exit }
            }')"
          [[ "$handle" =~ ^[0-9]+$ ]] || break
          nft delete rule inet veritasvpn_killswitch output handle "$handle" >/dev/null 2>&1 || break
        done
      fi
      if command -v iptables >/dev/null 2>&1 && iptables -L VERITASVPN_KILLSWITCH >/dev/null 2>&1; then
        while IFS= read -r line; do
          [[ "$line" == *"comment veritas-fresh"* ]] || continue
          spec="${line#-A VERITASVPN_KILLSWITCH }"
          # shellcheck disable=SC2086
          iptables -D VERITASVPN_KILLSWITCH $spec >/dev/null 2>&1 || true
        done < <(iptables -S VERITASVPN_KILLSWITCH 2>/dev/null || true)
      fi
    }
    cleanup() {
      delete_fresh_rules
      if [[ "$pin" == added ]]; then
        ip route del "$api_ip/32" via "$gw" dev "$gif" >/dev/null 2>&1 || true
      elif [[ "$pin" == replaced ]]; then
        ip route replace "$api_ip/32" ${old_via:+via "$old_via"} ${old_dev:+dev "$old_dev"} ${old_src:+src "$old_src"} >/dev/null 2>&1 || true
      fi
      [[ -n "$body" ]] && rm -f "$body"
      [[ -n "$cfg" ]] && rm -f "$cfg"
    }
    trap cleanup EXIT
    body="$(mktemp)"
    cfg="$(mktemp)"
    chmod 600 "$body" "$cfg" 2>/dev/null || true
    # The API /32 is pinned only when this call adds or replaces it, and only
    # that route is removed. A route that was already correct is left alone.
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
      [[ "$old_via" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || old_via=""
      [[ "$old_dev" =~ ^[A-Za-z0-9_.:-]{1,15}$ ]] || old_dev=""
      [[ "$old_src" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || old_src=""
      if [[ -z "$old_dev" && -z "$old_via" ]]; then
        if ip route add "$api_ip/32" via "$gw" dev "$gif" >/dev/null 2>&1; then
          pin="added"
        fi
      elif [[ "$old_via" == "$gw" && "$old_dev" == "$gif" ]]; then
        pin="kept"
      else
        if ip route replace "$api_ip/32" via "$gw" dev "$gif" >/dev/null 2>&1; then
          pin="replaced"
        fi
      fi
    fi
    if command -v nft >/dev/null 2>&1 && nft list table inet veritasvpn_killswitch >/dev/null 2>&1; then
      nft insert rule inet veritasvpn_killswitch output ip daddr "$api_ip" tcp dport 443 accept comment \"veritas-fresh\" >/dev/null 2>&1 || true
    fi
    if command -v iptables >/dev/null 2>&1 && iptables -L VERITASVPN_KILLSWITCH >/dev/null 2>&1; then
      iptables -I VERITASVPN_KILLSWITCH 1 -d "$api_ip" -p tcp --dport 443 -m comment --comment veritas-fresh -j ACCEPT >/dev/null 2>&1 || true
    fi
    printf 'header = "Authorization: Bearer %s"\n' "$token" > "$cfg"
    printf 'url = "https://api.veritasvpn.cloud/api/v1/wg/peers/%s"\n' "$peer" >> "$cfg"
    printf 'resolve = "api.veritasvpn.cloud:443:%s"\n' "$api_ip" >> "$cfg"
    printf 'interface = "%s"\n' "$gif" >> "$cfg"
    printf 'output = "%s"\n' "$body" >> "$cfg"
    printf 'write-out = "%%{http_code}"\n' >> "$cfg"
    printf 'silent\n' >> "$cfg"
    code="$(curl --config "$cfg" -4 --connect-timeout 2 --max-time 4 2>/dev/null || true)"
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

fresh_backoff_seconds() {
  case "${1:-}" in
    0) printf '30' ;;
    1) printf '60' ;;
    2) printf '120' ;;
    *) printf '300' ;;
  esac
}

# Prints "read STEP" when a fresh peer read is due, or "skip".
# STEP is the value to store after that read. A new network stores 0.
fresh_due() {
  local stored_net="$1" stored_at="$2" stored_step="$3" live="$4" now="$5" wait=300 next=0
  [[ "$stored_at" =~ ^[0-9]+$ ]] || stored_at=0
  [[ "$stored_step" =~ ^[0-9]+$ ]] || stored_step=0
  [[ "$now" =~ ^[0-9]+$ ]] || now=0
  if [[ -z "$stored_net" || "$stored_net" != "$live" ]]; then
    printf 'read 0\n'
    return 0
  fi
  wait="$(fresh_backoff_seconds "$stored_step")"
  if (( now - stored_at >= wait )); then
    next="$stored_step"
    if (( next < 3 )); then
      next=$((next + 1))
    fi
    printf 'read %s\n' "$next"
  else
    printf 'skip\n'
  fi
}

fresh_state_file() {
  [[ -n "${STATE_DIR:-}" ]] || return 1
  printf '%s/fresh-read' "$STATE_DIR"
}

fresh_state_value() {
  local key="$1" file=""
  file="$(fresh_state_file 2>/dev/null || true)"
  [[ -n "$file" && -f "$file" && ! -L "$file" ]] || return 0
  awk -F= -v wanted="$key" '$1 == wanted { print substr($0, index($0, "=") + 1); exit }' "$file" 2>/dev/null || true
}

save_fresh_state() {
  local net="$1" at="$2" step="$3" ep="$4" stealth="$5" file="" tmp=""
  file="$(fresh_state_file 2>/dev/null || true)" || return 0
  [[ "$net" =~ ^[A-Za-z0-9_.:|-]{1,80}$ ]] || return 0
  [[ "$at" =~ ^[0-9]+$ ]] || at=0
  [[ "$step" =~ ^[0-9]+$ ]] || step=0
  [[ "$ep" =~ ^[A-Za-z0-9.-]+:[0-9]{1,5}$ ]] || ep=""
  [[ "$stealth" =~ ^[A-Za-z0-9.-]+:[0-9]{1,5}$ ]] || stealth=""
  tmp="$(mktemp)"
  chmod 600 "$tmp" 2>/dev/null || true
  printf 'network=%s\nat=%s\nstep=%s\nep=%s\nstealth=%s\n' "$net" "$at" "$step" "$ep" "$stealth" > "$tmp"
  mv "$tmp" "$file" || { rm -f "$tmp"; return 0; }
  chmod 600 "$file" 2>/dev/null || true
}

# One fresh read per network change, then 30s, 60s, 120s, and 5 minute gaps.
# A skip reuses the cached endpoints for this network. A failed read clears them.
maybe_fresh_lines() {
  local gw="${1:-}" gif="${2:-}" live="${1}|${2}"
  local stored_net="" stored_at="" stored_step="" stored_ep="" stored_stealth=""
  local now="" decision="" next="" lines="" code="" ep="" st=""
  stored_net="$(fresh_state_value network)"
  stored_at="$(fresh_state_value at)"
  stored_step="$(fresh_state_value step)"
  stored_ep="$(fresh_state_value ep)"
  stored_stealth="$(fresh_state_value stealth)"
  now="$(date +%s 2>/dev/null || echo 0)"
  read -r decision next < <(fresh_due "$stored_net" "$stored_at" "$stored_step" "$live" "$now")
  if [[ "$decision" == skip ]]; then
    [[ "$stored_ep" =~ ^[A-Za-z0-9.-]+:[0-9]{1,5}$ ]] || stored_ep=""
    [[ "$stored_stealth" =~ ^[A-Za-z0-9.-]+:[0-9]{1,5}$ ]] || stored_stealth=""
    printf '200\n%s\n%s\n' "$stored_ep" "$stored_stealth"
    return 0
  fi
  [[ "$next" =~ ^[0-9]+$ ]] || next=0
  lines="$(read_fresh_lines "$gw" "$gif" || true)"
  code="$(printf '%s\n' "$lines" | sed -n '1p')"
  ep="$(printf '%s\n' "$lines" | sed -n '2p')"
  st="$(printf '%s\n' "$lines" | sed -n '3p')"
  [[ "$code" =~ ^[0-9]+$ ]] || code=0
  if [[ "$code" != 200 ]]; then
    ep=""
    st=""
  fi
  save_fresh_state "$live" "$now" "$next" "$ep" "$st"
  printf '%s\n%s\n%s\n' "$code" "$ep" "$st"
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
