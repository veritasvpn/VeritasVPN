# Publish the latest WireGuard handshake time for the unprivileged UI.
#
# Ubuntu's in-kernel interface answers `wg show` with "Operation not permitted"
# unless the caller has CAP_NET_ADMIN. There is no userspace socket to chown
# on that path, and handing the desktop user the UAPI dump would also hand
# them the interface private key (it is the first line). This file never
# prints that dump. It writes one integer to the per-user state directory.

handshake_from_dump() {
  # stdin is `wg show <iface> dump`. stdout is one non-negative integer.
  awk 'BEGIN { FS = "\t" } NR > 1 && $5 ~ /^[0-9]+$/ { if ($5+0 > max) max = $5+0 } END { printf "%d\n", max+0 }'
}

handshake_state_uid() {
  local dir="${1:-}" uid=""
  uid="${dir##*/}"
  [[ "$dir" == "/var/lib/veritasvpn/state/$uid" ]] || return 1
  [[ "$uid" =~ ^[0-9]+$ ]] || return 1
  [[ "$uid" != "0" ]] || return 1
  getent passwd "$uid" >/dev/null 2>&1 || return 1
  printf '%s\n' "$uid"
}

handshake_log_once() {
  local msg="$1"
  [[ "${HANDSHAKE_FAIL_LOGGED:-}" == "$msg" ]] && return 0
  HANDSHAKE_FAIL_LOGGED="$msg"
  command -v logger >/dev/null 2>&1 || return 0
  # One fixed line. Never the dump, the key, or the epoch.
  logger -t veritasvpn-handshake -- "$msg" >/dev/null 2>&1 || true
}

write_handshake_file() {
  local dir="$1" value="$2" uid="$3" tmp="" saved=""
  [[ "$value" =~ ^[0-9]+$ ]] || return 0
  [[ "$uid" =~ ^[0-9]+$ ]] || return 1
  [[ -d "$dir" && ! -L "$dir" ]] || return 1
  [[ -f "$dir/iface" && ! -L "$dir/iface" ]] || return 0
  if [[ -L "$dir/handshake" ]]; then
    rm -f "$dir/handshake" 9>&-
  fi
  saved="$(umask)"
  umask 077
  # Close the lock fd inside the substitution. The temp file must not inherit it.
  tmp="$(exec 9>&-; mktemp "$dir/.handshake.XXXXXX" 2>/dev/null || true)"
  umask "$saved"
  if [[ -z "$tmp" ]]; then
    handshake_log_once "could not publish the handshake file"
    return 1
  fi
  if ! printf '%s\n' "$value" > "$tmp"; then
    rm -f "$tmp" 9>&-
    handshake_log_once "could not publish the handshake file"
    return 1
  fi
  # GNU coreutils 9.4 rejects "chown <numeric-uid>:". The owner is enough;
  # mode 0600 does not grant the group access. chmod/chown of the final path
  # would follow a symlink, so they stay on the temp file.
  if ! chmod 600 "$tmp" 2>/dev/null 9>&- || ! chown "$uid" "$tmp" 2>/dev/null 9>&-; then
    rm -f "$tmp" 9>&-
    handshake_log_once "could not publish the handshake file"
    return 1
  fi
  if [[ -f "$dir/iface" && ! -L "$dir/iface" ]]; then
    if ! mv -f "$tmp" "$dir/handshake" 2>/dev/null 9>&-; then
      rm -f "$tmp" 9>&-
      handshake_log_once "could not publish the handshake file"
      return 1
    fi
  else
    rm -f "$tmp" 9>&-
    return 0
  fi
  HANDSHAKE_FAIL_LOGGED=""
  return 0
}

publish_watcher_once() {
  local dir="$1" uid="$2" hs="" status=0
  status=0
  # exec closes fd 9 for this subshell before wg starts. A later 9>&- on wg
  # would target an already-closed descriptor and is not required.
  hs="$(exec 9>&-; wg show veritas0 dump 2>/dev/null | handshake_from_dump)" || status=$?
  if [[ "$status" -ne 0 || ! "$hs" =~ ^[0-9]+$ ]]; then
    handshake_log_once "could not read the handshake"
    return 0
  fi
  write_handshake_file "$dir" "$hs" "$uid" || true
}

read_handshake_value() {
  local hs="" status=0
  # Drop stderr. A failed dump must not replace a value the UI already has.
  hs="$(wg show veritas0 dump 2>/dev/null | handshake_from_dump)" || status=$?
  if [[ "$status" -ne 0 ]]; then
    printf '\n'
    return 0
  fi
  [[ "$hs" =~ ^[0-9]+$ ]] || hs=0
  printf '%s\n' "$hs"
}

publish_handshake_once() {
  local dir="${1:-}" uid="" hs=""
  uid="$(handshake_state_uid "$dir" 2>/dev/null || true)"
  [[ -n "$uid" ]] || return 0
  hs="$(read_handshake_value)"
  [[ "$hs" =~ ^[0-9]+$ ]] || return 0
  write_handshake_file "$dir" "$hs" "$uid"
}

handshake_publisher_alive() {
  local dir="$1" pid="" uid="" command=""
  [[ -f "$dir/handshake.pid" && ! -L "$dir/handshake.pid" ]] || return 1
  pid="$(cat "$dir/handshake.pid" 2>/dev/null || true)"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  uid="$(ps -o uid= -p "$pid" 2>/dev/null | tr -d ' ' || true)"
  command="$(ps -o command= -p "$pid" 2>/dev/null || true)"
  [[ "$uid" == "0" && "$command" == *"/var/lib/veritasvpn/handshake.sh $dir"* ]]
}

stop_handshake_publisher() {
  local dir="${1:-}" pid="" uid="" command="" pgid="" _
  [[ -n "$dir" && -d "$dir" && ! -L "$dir" ]] || return 0
  if [[ -f "$dir/handshake.pid" && ! -L "$dir/handshake.pid" ]]; then
    pid="$(cat "$dir/handshake.pid" 2>/dev/null || true)"
    if [[ "$pid" =~ ^[0-9]+$ ]]; then
      uid="$(ps -o uid= -p "$pid" 2>/dev/null | tr -d ' ' || true)"
      command="$(ps -o command= -p "$pid" 2>/dev/null || true)"
      pgid="$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ' || true)"
      if [[ "$uid" == "0" && "$command" == *"/var/lib/veritasvpn/handshake.sh $dir"* ]]; then
        # The watcher is started with setsid, so its pid is the process group.
        # Killing only the pid leaves sleep/wg holding the lock fd.
        if [[ "$pgid" == "$pid" ]]; then
          kill -- "-$pid" 2>/dev/null || true
        else
          kill "$pid" 2>/dev/null || true
        fi
        for _ in 1 2 3 4 5 6 7 8 9 10; do
          kill -0 "$pid" 2>/dev/null || break
          sleep 0.1
        done
        if kill -0 "$pid" 2>/dev/null; then
          command="$(ps -o command= -p "$pid" 2>/dev/null || true)"
          pgid="$(ps -o pgid= -p "$pid" 2>/dev/null | tr -d ' ' || true)"
          if [[ "$command" == *"/var/lib/veritasvpn/handshake.sh $dir"* && "$pgid" == "$pid" ]]; then
            kill -9 -- "-$pid" 2>/dev/null || true
          elif [[ "$command" == *"/var/lib/veritasvpn/handshake.sh $dir"* ]]; then
            kill -9 "$pid" 2>/dev/null || true
          fi
        fi
      fi
    fi
  fi
  rm -f "$dir/handshake.pid" "$dir/handshake" "$dir"/.handshake.??????
}

install_handshake_helper() {
  local dest="/var/lib/veritasvpn/handshake.sh" tmp="" fn=""
  install -d -o root -g root -m 0755 /var/lib/veritasvpn
  if [[ -L "$dest" ]]; then
    rm -f "$dest"
  fi
  fn="$(declare -f handshake_from_dump 2>/dev/null || true)
$(declare -f handshake_log_once 2>/dev/null || true)
$(declare -f write_handshake_file 2>/dev/null || true)
$(declare -f publish_watcher_once 2>/dev/null || true)"
  [[ "$fn" == handshake_from_dump* ]] || return 1
  tmp="$(mktemp /var/lib/veritasvpn/.handshake.sh.XXXXXX 2>/dev/null || true)"
  [[ -n "$tmp" ]] || return 1
  cat > "$tmp" <<'HANDSHAKE_HEAD'
#!/bin/bash
# Root watcher. wg's dump includes the interface private key and is never printed.
set -uo pipefail
HANDSHAKE_HEAD
  printf '%s\n' "$fn" >> "$tmp" || { rm -f "$tmp"; return 1; }
  cat >> "$tmp" <<'HANDSHAKE_BODY'
dir="${1:-}"
uid="${dir##*/}"
[[ "$dir" == "/var/lib/veritasvpn/state/$uid" ]] || exit 0
[[ "$uid" =~ ^[0-9]+$ && "$uid" != "0" ]] || exit 0
getent passwd "$uid" >/dev/null 2>&1 || exit 0
[[ -d "$dir" && ! -L "$dir" ]] || exit 0
if [[ -L "$dir/handshake.lock" ]]; then
  rm -f "$dir/handshake.lock"
fi
if command -v flock >/dev/null 2>&1; then
  exec 9>"$dir/handshake.lock"
  flock -n 9 || exit 0
fi
printf '%s\n' "$$" > "$dir/handshake.pid" || exit 0
chmod 600 "$dir/handshake.pid" 2>/dev/null 9>&- || true
while [[ -f "$dir/iface" && ! -L "$dir/iface" ]]; do
  publish_watcher_once "$dir" "$uid" || true
  sleep 2 9>&-
done
rm -f "$dir/handshake" "$dir/handshake.pid" 9>&-
HANDSHAKE_BODY
  chmod 755 "$tmp" || { rm -f "$tmp"; return 1; }
  chown root:root "$tmp" || { rm -f "$tmp"; return 1; }
  mv -f "$tmp" "$dest" || { rm -f "$tmp"; return 1; }
}

start_handshake_publisher() {
  local dir="${1:-}"
  handshake_state_uid "$dir" >/dev/null 2>&1 || return 0
  [[ -d "$dir" && ! -L "$dir" ]] || return 0
  [[ -f "$dir/iface" && ! -L "$dir/iface" ]] || return 0
  publish_handshake_once "$dir" || true
  install_handshake_helper || return 0
  if handshake_publisher_alive "$dir"; then
    return 0
  fi
  rm -f "$dir/handshake.pid"
  if command -v setsid >/dev/null 2>&1; then
    nohup setsid env -i PATH="/usr/sbin:/usr/bin:/sbin:/bin" /var/lib/veritasvpn/handshake.sh "$dir" >/dev/null 2>&1 &
  else
    nohup env -i PATH="/usr/sbin:/usr/bin:/sbin:/bin" /var/lib/veritasvpn/handshake.sh "$dir" >/dev/null 2>&1 &
  fi
  disown 2>/dev/null || true
  return 0
}
