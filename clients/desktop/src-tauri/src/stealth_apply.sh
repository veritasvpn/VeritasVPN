# Restart bundled wstunnel once when stealth_decide says DIAL.
# Routes and the firewall table stay in place. The drop rule is not removed.

# Absolute path, no traversal, no shell metacharacters. Prefix matches the
# connect-time check: starts with /, no "..".
stealth_paths_ok() {
  local bin="${1:-}" prefix="${2:-}"
  [[ "$bin" == /* ]] || return 1
  [[ "$bin" != *..* ]] || return 1
  [[ "$bin" =~ ^/[A-Za-z0-9._~+/-]+$ ]] || return 1
  [[ "$prefix" == /* ]] || return 1
  [[ "$prefix" != *..* ]] || return 1
  [[ "$prefix" =~ ^[A-Za-z0-9/._~-]+$ ]] || return 1
  return 0
}

load_stealth_state() {
  local f="$STATE_DIR/stealth-roam"
  STEALTH_ISSUED=""
  STEALTH_ISSUED_AT=0
  STEALTH_BASELINE_RX=0
  STEALTH_DIALS=0
  [[ -f "$f" && ! -L "$f" ]] || return 0
  STEALTH_ISSUED="$(awk -F= '$1=="issued"{print substr($0, index($0, "=") + 1); exit}' "$f" 2>/dev/null || true)"
  STEALTH_ISSUED_AT="$(awk -F= '$1=="issued_at"{print substr($0, index($0, "=") + 1); exit}' "$f" 2>/dev/null || true)"
  STEALTH_BASELINE_RX="$(awk -F= '$1=="baseline_rx"{print substr($0, index($0, "=") + 1); exit}' "$f" 2>/dev/null || true)"
  STEALTH_DIALS="$(awk -F= '$1=="dials"{print substr($0, index($0, "=") + 1); exit}' "$f" 2>/dev/null || true)"
  [[ "$STEALTH_ISSUED_AT" =~ ^[0-9]+$ ]] || STEALTH_ISSUED_AT=0
  [[ "$STEALTH_BASELINE_RX" =~ ^[0-9]+$ ]] || STEALTH_BASELINE_RX=0
  [[ "$STEALTH_DIALS" =~ ^[0-9]+$ ]] || STEALTH_DIALS=0
  case "$STEALTH_ISSUED" in
    *[!A-Za-z0-9_.:|-]*) STEALTH_ISSUED="" ;;
  esac
}

save_stealth_state() {
  local f="$STATE_DIR/stealth-roam" tmp
  tmp="$(mktemp)"
  chmod 600 "$tmp" 2>/dev/null || true
  printf 'issued=%s\nissued_at=%s\nbaseline_rx=%s\ndials=%s\n' "$1" "$2" "$3" "$4" > "$tmp"
  mv "$tmp" "$f"
}

# Keep one veritas-outside accept for this ip/port/proto and delete the rest,
# including accepts left behind for a previous server address.
allow_outside_ip() {
  local ip="$1" port="$2" proto="$3"
  local keeper="" handle="" line="" spec="" _i
  [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 0
  [[ "$port" =~ ^[0-9]+$ ]] || return 0
  [[ "$proto" == tcp || "$proto" == udp ]] || return 0
  if command -v nft >/dev/null 2>&1 && nft list table inet veritasvpn_killswitch >/dev/null 2>&1; then
    keeper="$(nft -a list chain inet veritasvpn_killswitch output 2>/dev/null | awk -v ip="$ip" -v port="$port" -v proto="$proto" '
      index($0, "comment \"veritas-outside\"") && index($0, "ip daddr " ip " ") && index($0, proto " dport " port) && index($0, "accept") {
        for (i = 1; i <= NF; i++) if ($i == "handle") { print $(i + 1); exit }
      }')"
    [[ "$keeper" =~ ^[0-9]+$ ]] || keeper=""
    for _i in 1 2 3 4 5 6 7 8 9 10 11 12; do
      handle="$(nft -a list chain inet veritasvpn_killswitch output 2>/dev/null | awk -v keep="$keeper" '
        index($0, "comment \"veritas-outside\"") {
          h=""
          for (i = 1; i <= NF; i++) if ($i == "handle") h=$(i+1)
          if (h ~ /^[0-9]+$/ && h != keep) { print h; exit }
        }')"
      [[ "$handle" =~ ^[0-9]+$ ]] || break
      nft delete rule inet veritasvpn_killswitch output handle "$handle" >/dev/null 2>&1 || break
    done
    if [[ -z "$keeper" ]]; then
      nft insert rule inet veritasvpn_killswitch output ip daddr "$ip" "$proto" dport "$port" accept comment \"veritas-outside\" >/dev/null 2>&1 || true
    fi
  fi
  if command -v iptables >/dev/null 2>&1 && iptables -L VERITASVPN_KILLSWITCH >/dev/null 2>&1; then
    keeper=""
    while IFS= read -r line; do
      [[ "$line" == *"comment veritas-outside"* ]] || continue
      if [[ -z "$keeper" && "$line" == *"-d $ip"* && "$line" == *"-p $proto"* && "$line" == *"dport $port"* ]]; then
        keeper=1
        continue
      fi
      spec="${line#-A VERITASVPN_KILLSWITCH }"
      # shellcheck disable=SC2086
      iptables -D VERITASVPN_KILLSWITCH $spec >/dev/null 2>&1 || true
    done < <(iptables -S VERITASVPN_KILLSWITCH 2>/dev/null || true)
    if [[ -z "$keeper" ]]; then
      iptables -I VERITASVPN_KILLSWITCH 1 -d "$ip" -p "$proto" --dport "$port" -m comment --comment veritas-outside -j ACCEPT >/dev/null 2>&1 || true
    fi
  fi
}

stop_wstunnel_pid() {
  local file="$STATE_DIR/wstunnel.pid" pid="" uid="" command=""
  [[ -f "$file" && ! -L "$file" ]] || return 0
  pid="$(cat "$file" 2>/dev/null || true)"
  if [[ "$pid" =~ ^[0-9]+$ ]]; then
    uid="$(ps -o uid= -p "$pid" 2>/dev/null | tr -d ' ' || true)"
    command="$(ps -o command= -p "$pid" 2>/dev/null || true)"
    if [[ "$uid" == 0 && "$command" == *wstunnel* ]]; then
      kill "$pid" 2>/dev/null || true
      local _i
      for _i in 1 2 3 4 5 6 7 8 9 10; do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.1
      done
      kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
    fi
  fi
  rm -f "$file"
}

# Move the host route onto the address wstunnel actually used.
pin_replaced_ip() {
  local old_ip="$1" new_ip="$2"
  [[ "$new_ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 0
  [[ "$old_ip" == "$new_ip" ]] && return 0
  ip route del "$old_ip" 2>/dev/null || true
  ip route del "$old_ip/32" 2>/dev/null || true
  ip route del "$new_ip" 2>/dev/null || true
  ip route del "$new_ip/32" 2>/dev/null || true
  if [[ -n "${NEW_SRC:-}" ]] && [[ "$(same_ipv4_24 "$new_ip" "$NEW_SRC")" == yes ]]; then
    ip route replace "$new_ip/32" dev "$NEW_GW_IF" src "$NEW_SRC" 2>/dev/null || \
      ip route replace "$new_ip" dev "$NEW_GW_IF" 2>/dev/null || true
  elif [[ -n "${NEW_GW:-}" ]]; then
    ip route replace "$new_ip/32" via "$NEW_GW" ${NEW_GW_IF:+dev "$NEW_GW_IF"} 2>/dev/null || \
      ip route replace "$new_ip" via "$NEW_GW" ${NEW_GW_IF:+dev "$NEW_GW_IF"} 2>/dev/null || true
  fi
  CHOOSE_IP="$new_ip"
}

start_wstunnel_pinned() {
  local dial="${STEALTH_DIAL:-$STEALTH_REMOTE}" ip="${RESOLVED_STEALTH_IP:-$CHOOSE_IP}"
  local line url="" sni="" pid="" actual=""
  stealth_paths_ok "${WSTUNNEL_BIN:-}" "${STEALTH_PREFIX:-}" || return 1
  [[ -n "$dial" && -x "${WSTUNNEL_BIN:-}" ]] || return 1
  while IFS= read -r line; do
    case "$line" in
      url\ *) url="${line#url }" ;;
      sni\ *) sni="${line#sni }" ;;
    esac
  done < <(wstunnel_prepare_line "$dial" "$ip")
  [[ "$url" == wss://* ]] || return 1
  local wst_log="$STATE_DIR/wstunnel.log"
  : > "$wst_log" 2>/dev/null || true
  chmod 600 "$wst_log" 2>/dev/null || true
  stop_wstunnel_pid
  if [[ -n "$sni" && "$sni" =~ ^[A-Za-z0-9.-]+$ ]]; then
    "$WSTUNNEL_BIN" client \
      --tls-sni-override "$sni" \
      --http-upgrade-path-prefix "$STEALTH_PREFIX" \
      -L "udp://127.0.0.1:41820:127.0.0.1:51820?timeout_sec=0" \
      "$url" >"$wst_log" 2>&1 &
  else
    "$WSTUNNEL_BIN" client \
      --http-upgrade-path-prefix "$STEALTH_PREFIX" \
      -L "udp://127.0.0.1:41820:127.0.0.1:51820?timeout_sec=0" \
      "$url" >"$wst_log" 2>&1 &
  fi
  pid=$!
  echo "$pid" > "$STATE_DIR/wstunnel.pid"
  sleep 0.4
  if ! kill -0 "$pid" 2>/dev/null; then
    rm -f "$STATE_DIR/wstunnel.pid"
    return 1
  fi
  actual="$(actual_wstunnel_ip "$pid")"
  if [[ -n "$actual" ]]; then
    pin_replaced_ip "$CHOOSE_IP" "$actual"
    allow_outside_ip "$CHOOSE_IP" "$(port_of "$dial" 2>/dev/null || true)" tcp
  fi
  STEALTH_BOUND_NOW="${NEW_GW}|${NEW_GW_IF}"
  return 0
}

maybe_restart_stealth() {
  local live="" bound="" open=0 pid="" comm="" hs=0 rx=0 now_sec=0
  local line="" action="" issued="" issued_at="" baseline="" dials=""
  STEALTH_BOUND_NOW=""
  [[ -n "${STEALTH_REMOTE:-}" ]] || return 0
  [[ -n "${STATE_DIR:-}" && -d "$STATE_DIR" ]] || return 0
  live="${NEW_GW}|${NEW_GW_IF}"
  [[ -n "${NEW_GW:-}" && -n "${NEW_GW_IF:-}" ]] || return 0
  bound="$(meta_value stealth_bound 2>/dev/null || true)"
  case "$bound" in
    *[!A-Za-z0-9_.:|-]*) bound="" ;;
  esac
  if [[ -f "$STATE_DIR/wstunnel.pid" && ! -L "$STATE_DIR/wstunnel.pid" ]]; then
    pid="$(cat "$STATE_DIR/wstunnel.pid" 2>/dev/null || true)"
    if [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/comm" ]]; then
      comm="$(tr -d '\0' < "/proc/$pid/comm" 2>/dev/null || true)"
      [[ "$comm" == *wstunnel* ]] && open=1
    fi
  fi
  if command -v wg >/dev/null 2>&1; then
    line="$(wg show veritas0 dump 2>/dev/null | awk 'NR==2 { print; exit }')"
    hs="$(printf '%s' "$line" | awk -F '\t' '{ print $5 }')"
    rx="$(printf '%s' "$line" | awk -F '\t' '{ print $6 }')"
  fi
  [[ "$hs" =~ ^[0-9]+$ ]] || hs=0
  [[ "$rx" =~ ^[0-9]+$ ]] || rx=0
  now_sec="$(date +%s 2>/dev/null || echo 0)"
  [[ "$now_sec" =~ ^[0-9]+$ ]] || now_sec=0
  load_stealth_state
  line="$(stealth_decide "$live" "$bound" "$open" 0 "$rx" 0 "$hs" "$now_sec" \
    "$STEALTH_ISSUED" "$STEALTH_ISSUED_AT" "$STEALTH_BASELINE_RX" "$STEALTH_DIALS" 0)"
  action="${line%%$'\t'*}"
  issued="$(printf '%s' "$line" | awk -F '\t' '{ print $2 }')"
  issued_at="$(printf '%s' "$line" | awk -F '\t' '{ print $3 }')"
  baseline="$(printf '%s' "$line" | awk -F '\t' '{ print $4 }')"
  dials="$(printf '%s' "$line" | awk -F '\t' '{ print $5 }')"
  save_stealth_state "$issued" "$issued_at" "$baseline" "$dials"
  [[ "$action" == DIAL ]] || return 0
  # Record the network before start so a failed dial still counts as this network.
  STEALTH_BOUND_NOW="$live"
  start_wstunnel_pinned || true
}
