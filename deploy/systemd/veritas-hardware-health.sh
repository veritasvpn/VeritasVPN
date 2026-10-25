#!/usr/bin/env bash
# Host hardware, package, firmware, and backup gauges for the node-exporter
# textfile collector.
#
# Firmware ignore list (repo default deploy/systemd/firmware-ignore.conf):
#   sudo install -m 0755 deploy/systemd/veritas-hardware-health.sh /usr/local/sbin/veritas-hardware-health
#   sudo install -d -m 0755 /etc/veritasvpn
#   sudo install -m 0644 deploy/systemd/firmware-ignore.conf /etc/veritasvpn/firmware-ignore.conf
#   sudo systemctl start veritas-hardware-health.service
#
# FIRMWARE_IGNORE_FILE overrides /etc/veritasvpn/firmware-ignore.conf.
# BIOS_VERSION_FILE overrides the sysfs BIOS version (used by tests).
# TEXTFILE_DIR overrides /var/lib/veritasvpn/metrics.
set -euo pipefail

# Escape a string for a Prometheus label value.
prom_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/ }"
  value="${value//$'\r'/ }"
  printf '%s' "$value"
}

# Return success when $1 is a strictly older dotted-integer version than $2.
version_less() {
  local left="$1"
  local right="$2"
  [[ "$left" =~ ^[0-9]+(\.[0-9]+)*$ ]] || return 1
  [[ "$right" =~ ^[0-9]+(\.[0-9]+)*$ ]] || return 1
  local IFS=.
  local -a left_parts right_parts
  local i count left_n right_n
  read -r -a left_parts <<<"$left"
  read -r -a right_parts <<<"$right"
  count=${#left_parts[@]}
  if (( ${#right_parts[@]} > count )); then
    count=${#right_parts[@]}
  fi
  for ((i = 0; i < count; i++)); do
    left_n=$((10#${left_parts[i]:-0}))
    right_n=$((10#${right_parts[i]:-0}))
    if (( left_n < right_n )); then
      return 0
    fi
    if (( left_n > right_n )); then
      return 1
    fi
  done
  return 1
}

# BIOS_MINIMUM, FW_IGNORE_ANY, and FW_IGNORE_VERSION are replaced on each call.
load_firmware_ignore() {
  local file="$1"
  local line id ver
  BIOS_MINIMUM=""
  unset FW_IGNORE_VERSION FW_IGNORE_ANY
  declare -gA FW_IGNORE_VERSION=()
  declare -gA FW_IGNORE_ANY=()
  [[ -f "$file" && -r "$file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -n "$line" ]] || continue
    id="${line%%[[:space:]]*}"
    if [[ "$line" == "$id" ]]; then
      ver=""
    else
      ver="${line#"$id"}"
      ver="${ver#"${ver%%[![:space:]]*}"}"
      ver="${ver%"${ver##*[![:space:]]}"}"
      ver="${ver%%[[:space:]]*}"
    fi
    if [[ "$id" == "bios_minimum" ]]; then
      BIOS_MINIMUM="$ver"
      continue
    fi
    if [[ -n "$ver" ]]; then
      if [[ -n "${FW_IGNORE_ANY[$id]+x}" ]]; then
        unset "FW_IGNORE_ANY[$id]"
      fi
      FW_IGNORE_VERSION["$id"]="$ver"
    else
      if [[ -n "${FW_IGNORE_VERSION[$id]+x}" ]]; then
        unset "FW_IGNORE_VERSION[$id]"
      fi
      FW_IGNORE_ANY["$id"]=1
    fi
  done <"$file"
}

firmware_update_ignored() {
  local id="$1"
  local available="$2"
  [[ -n "$id" ]] || return 1
  if [[ -n "${FW_IGNORE_ANY[$id]+x}" ]]; then
    return 0
  fi
  if [[ -n "${FW_IGNORE_VERSION[$id]+x}" && "${FW_IGNORE_VERSION[$id]}" == "$available" ]]; then
    return 0
  fi
  return 1
}

# One JSON object per device that has a release. The offered version is the
# newest numeric release; fwupdmgr does not promise array order.
firmware_jq_filter() {
  cat <<'EOF'
[.Devices[]?
  | select(((.Releases // []) | length) > 0)
  | . as $d
  | [($d.Releases // [])[] | (.Version // "") | select(length > 0)] as $versions
  | (if ($versions | length) == 0 then "unknown"
     else
       ($versions
         | map({v: ., p: (if test("^[0-9]+(\\.[0-9]+)*$") then [splits("\\.") | tonumber] else [] end)})
         | max_by(.p).v)
     end) as $available
  | {
      name: (
        ($d.Name // "")
        | gsub("[\n\r\t]"; " ")
        | if length == 0 then "unknown" else . end
      ),
      id: ($d.DeviceId // $d.DeviceID // ""),
      current: (if (($d.Version // "") | length) == 0 then "unknown" else $d.Version end),
      available: $available
    }
] | .[]
EOF
}

# Sets FIRMWARE_UPDATES_PENDING, FIRMWARE_DETAIL_METRICS, and BIOS_METRICS.
# json_file may be empty when fwupdmgr is not available. bios_version may be empty.
render_firmware_metrics() {
  local json_file="$1"
  local ignore_file="$2"
  local bios_version="$3"
  local filter row name id current available ignored key
  local -a detail_lines=()
  local -A seen=()
  local pending=0
  local minimum_label version_label below

  load_firmware_ignore "$ignore_file"
  FIRMWARE_UPDATES_PENDING=0
  FIRMWARE_DETAIL_METRICS=""
  BIOS_METRICS=""

  if [[ -n "$json_file" && -s "$json_file" ]] && command -v jq >/dev/null 2>&1; then
    filter="$(firmware_jq_filter)"
    while IFS= read -r row; do
      [[ -n "$row" ]] || continue
      name="$(jq -r '.name' <<<"$row")"
      id="$(jq -r '.id' <<<"$row")"
      current="$(jq -r '.current' <<<"$row")"
      available="$(jq -r '.available' <<<"$row")"
      name="${name#"${name%%[![:space:]]*}"}"
      name="${name%"${name##*[![:space:]]}"}"
      [[ -n "$name" ]] || name="unknown"
      [[ -n "$current" && "$current" != "null" ]] || current="unknown"
      [[ -n "$available" && "$available" != "null" ]] || available="unknown"
      if firmware_update_ignored "$id" "$available"; then
        ignored="true"
      else
        ignored="false"
        pending=$((pending + 1))
      fi
      key="${name}"$'\t'"${current}"$'\t'"${available}"$'\t'"${ignored}"
      if [[ -n "${seen[$key]+x}" ]]; then
        seen[$key]=$((seen[$key] + 1))
        name="${name} (${seen[$key]})"
      else
        seen[$key]=1
      fi
      detail_lines+=("veritas_host_firmware_update_available{device=\"$(prom_escape "$name")\",current=\"$(prom_escape "$current")\",available=\"$(prom_escape "$available")\",ignored=\"${ignored}\"} 1")
    done < <(jq -c "$filter" "$json_file" 2>/dev/null || true)
  fi

  FIRMWARE_UPDATES_PENDING=$pending
  if ((${#detail_lines[@]} > 0)); then
    FIRMWARE_DETAIL_METRICS="$(printf '%s\n' \
      '# HELP veritas_host_firmware_update_available Firmware update offered for one device. ignored="true" is excluded from veritas_host_firmware_updates_pending.' \
      '# TYPE veritas_host_firmware_update_available gauge' \
      "${detail_lines[@]}")"
  fi

  bios_version="${bios_version#"${bios_version%%[![:space:]]*}"}"
  bios_version="${bios_version%"${bios_version##*[![:space:]]}"}"
  if [[ -n "$BIOS_MINIMUM" && -n "$bios_version" \
    && "$BIOS_MINIMUM" =~ ^[0-9]+(\.[0-9]+)*$ \
    && "$bios_version" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
    below=0
    if version_less "$bios_version" "$BIOS_MINIMUM"; then
      below=1
    fi
    minimum_label="$(prom_escape "$BIOS_MINIMUM")"
    version_label="$(prom_escape "$bios_version")"
    BIOS_METRICS="$(printf '%s\n' \
      '# HELP veritas_host_bios_below_minimum 1 when the installed BIOS is older than bios_minimum in the firmware ignore file.' \
      '# TYPE veritas_host_bios_below_minimum gauge' \
      "veritas_host_bios_below_minimum{version=\"${version_label}\",minimum=\"${minimum_label}\"} ${below}")"
  fi
}

read_installed_bios_version() {
  local path value
  if [[ -n "${BIOS_VERSION_FILE:-}" ]]; then
    if [[ -r "$BIOS_VERSION_FILE" ]]; then
      tr -d '[:space:]' <"$BIOS_VERSION_FILE"
    fi
    return 0
  fi
  path="/sys/class/dmi/id/bios_version"
  if [[ -r "$path" ]]; then
    tr -d '[:space:]' <"$path"
    return 0
  fi
  if command -v dmidecode >/dev/null 2>&1; then
    value="$(dmidecode -s bios-version 2>/dev/null || true)"
    value="${value%%$'\n'*}"
    printf '%s' "$value" | tr -d '[:space:]'
  fi
}

append_metric_block() {
  local block="$1"
  [[ -n "$block" ]] || return 0
  printf '\n%s\n' "$block" >>"$METRICS_TMP"
}

cleanup_hardware_metrics() {
  rm -f "${METRICS_TMP:-}" "${FIRMWARE_JSON_TMP:-}"
}

main() {
  local smart_available smart_healthy temperature updates_pending security_updates_pending
  local reboot_required firmware_updates_pending backup_age_seconds backup_verify_ok
  local backup_restore_rehearsal_ok external_vpn_synthetic_age_seconds external_vpn_synthetic_success
  local disk status attrs value apt_simulation latest_backup synthetic_json synthetic_conclusion
  local synthetic_updated synthetic_ts bios_version

  TEXTFILE_DIR="${TEXTFILE_DIR:-/var/lib/veritasvpn/metrics}"
  install -d -m 755 "$TEXTFILE_DIR"
  METRICS_TMP="$(mktemp "$TEXTFILE_DIR/veritas_hardware.prom.XXXXXX")"
  FIRMWARE_JSON_TMP=""
  trap cleanup_hardware_metrics EXIT

  smart_available=0
  smart_healthy=1
  temperature=""
  updates_pending=0
  security_updates_pending=0
  reboot_required=0
  firmware_updates_pending=0
  backup_age_seconds=-1
  backup_verify_ok=0
  backup_restore_rehearsal_ok=0
  external_vpn_synthetic_age_seconds=-1
  external_vpn_synthetic_success=0

  if command -v smartctl >/dev/null 2>&1; then
    smart_available=1
    while read -r disk; do
      [[ -n "$disk" ]] || continue
      status="$(smartctl -H "/dev/$disk" 2>/dev/null || true)"
      if grep -Eq 'SMART overall-health self-assessment test result: PASSED|SMART Health Status: OK' <<<"$status"; then
        :
      elif grep -Eq 'SMART.*FAILED|SMART overall-health.*: [^P]|SMART Health Status: [^O]' <<<"$status"; then
        smart_healthy=0
      fi
      attrs="$(smartctl -A "/dev/$disk" 2>/dev/null || true)"
      value="$(awk '/Temperature_Celsius|Temperature:/{for (i=NF; i>0; i--) if ($i ~ /^[0-9]+$/) {print $i; exit}}' <<<"$attrs")"
      if [[ "$value" =~ ^[0-9]+$ ]] && { [[ -z "$temperature" ]] || (( value > temperature )); }; then
        temperature="$value"
      fi
    done < <(lsblk -dn -o NAME,TYPE | awk '$2 == "disk" {print $1}')
  fi

  if [[ -z "$temperature" ]] && command -v sensors >/dev/null 2>&1; then
    temperature="$(sensors 2>/dev/null | awk '/Package id 0:|Tctl:/{for (i=1; i<=NF; i++) if ($i ~ /^\+[0-9]+(\.[0-9]+)?°C$/) {v=$i; gsub(/[+°C]/, "", v); print int(v); exit}}')"
  fi

  if command -v apt-get >/dev/null 2>&1; then
    apt_simulation="$(apt-get -s upgrade 2>/dev/null || true)"
    updates_pending="$(awk '/^Inst / {count++} END {print count+0}' <<<"$apt_simulation")"
    security_updates_pending="$(awk '/^Inst / && /security/ {count++} END {print count+0}' <<<"$apt_simulation")"
  fi

  [[ -e /var/run/reboot-required ]] && reboot_required=1

  bios_version="$(read_installed_bios_version)"
  if command -v fwupdmgr >/dev/null 2>&1 && command -v jq >/dev/null 2>&1; then
    FIRMWARE_JSON_TMP="$(mktemp)"
    fwupdmgr get-upgrades --json --no-unreported-check >"$FIRMWARE_JSON_TMP" 2>/dev/null || true
  fi
  render_firmware_metrics "$FIRMWARE_JSON_TMP" "${FIRMWARE_IGNORE_FILE:-/etc/veritasvpn/firmware-ignore.conf}" "$bios_version"
  firmware_updates_pending="$FIRMWARE_UPDATES_PENDING"

  latest_backup="$(find /var/backups/veritasvpn -maxdepth 1 -type f -name 'veritasvpn-*.tar.gz.enc' -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr | head -1 | cut -d' ' -f2- || true)"
  if [[ -n "$latest_backup" ]]; then
    backup_age_seconds=$(( $(date +%s) - $(stat -c %Y "$latest_backup") ))
  fi
  [[ "$(systemctl show veritas-backup-verify.service -p Result --value 2>/dev/null || true)" == "success" ]] && backup_verify_ok=1
  [[ "$(systemctl show veritas-backup-restore-rehearsal.service -p Result --value 2>/dev/null || true)" == "success" ]] && backup_restore_rehearsal_ok=1

  if command -v curl >/dev/null 2>&1 && command -v jq >/dev/null 2>&1; then
    synthetic_json="$(curl -fsS --max-time 10 \
      'https://api.github.com/repos/veritasvpn/VeritasVPN/actions/workflows/vpn-e2e.yml/runs?status=completed&per_page=1' \
      2>/dev/null || true)"
    synthetic_conclusion="$(jq -r '.workflow_runs[0].conclusion // empty' <<<"$synthetic_json" 2>/dev/null || true)"
    synthetic_updated="$(jq -r '.workflow_runs[0].updated_at // empty' <<<"$synthetic_json" 2>/dev/null || true)"
    if [[ -n "$synthetic_updated" ]]; then
      synthetic_ts="$(date -d "$synthetic_updated" +%s 2>/dev/null || printf '0')"
      if (( synthetic_ts > 0 )); then
        external_vpn_synthetic_age_seconds=$(( $(date +%s) - synthetic_ts ))
      fi
    fi
    [[ "$synthetic_conclusion" == "success" ]] && external_vpn_synthetic_success=1
  fi

  cat >"$METRICS_TMP" <<EOF
# HELP veritas_hardware_smart_available SMART telemetry availability.
# TYPE veritas_hardware_smart_available gauge
veritas_hardware_smart_available $smart_available
# HELP veritas_hardware_smart_healthy Whether all detected SMART-capable disks report healthy.
# TYPE veritas_hardware_smart_healthy gauge
veritas_hardware_smart_healthy $smart_healthy
# HELP veritas_host_updates_pending Number of installable operating-system package updates.
# TYPE veritas_host_updates_pending gauge
veritas_host_updates_pending $updates_pending
# HELP veritas_host_security_updates_pending Number of installable security updates.
# TYPE veritas_host_security_updates_pending gauge
veritas_host_security_updates_pending $security_updates_pending
# HELP veritas_host_reboot_required Whether the operating system requires a reboot.
# TYPE veritas_host_reboot_required gauge
veritas_host_reboot_required $reboot_required
# HELP veritas_host_firmware_updates_pending Number of installed devices with a firmware update that is not on the ignore list.
# TYPE veritas_host_firmware_updates_pending gauge
veritas_host_firmware_updates_pending $firmware_updates_pending
# HELP veritas_backup_age_seconds Age of the newest encrypted local backup, or -1 when absent.
# TYPE veritas_backup_age_seconds gauge
veritas_backup_age_seconds $backup_age_seconds
# HELP veritas_backup_verify_ok Whether the latest backup verification service completed successfully.
# TYPE veritas_backup_verify_ok gauge
veritas_backup_verify_ok $backup_verify_ok
# HELP veritas_backup_restore_rehearsal_ok Whether the latest isolated restore rehearsal completed successfully.
# TYPE veritas_backup_restore_rehearsal_ok gauge
veritas_backup_restore_rehearsal_ok $backup_restore_rehearsal_ok
# HELP veritas_external_vpn_synthetic_age_seconds Age of the latest completed external VPN synthetic run, or -1 when unavailable.
# TYPE veritas_external_vpn_synthetic_age_seconds gauge
veritas_external_vpn_synthetic_age_seconds $external_vpn_synthetic_age_seconds
# HELP veritas_external_vpn_synthetic_success Whether the latest completed external VPN synthetic run succeeded.
# TYPE veritas_external_vpn_synthetic_success gauge
veritas_external_vpn_synthetic_success $external_vpn_synthetic_success
EOF
  if [[ "$temperature" =~ ^[0-9]+$ ]]; then
    cat >>"$METRICS_TMP" <<EOF
# HELP veritas_hardware_max_temperature_celsius Highest available disk or CPU temperature in Celsius.
# TYPE veritas_hardware_max_temperature_celsius gauge
veritas_hardware_max_temperature_celsius $temperature
EOF
  fi
  append_metric_block "$FIRMWARE_DETAIL_METRICS"
  append_metric_block "$BIOS_METRICS"
  chmod 0644 "$METRICS_TMP"
  mv "$METRICS_TMP" "$TEXTFILE_DIR/veritas_hardware.prom"
  METRICS_TMP=""
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
