#!/usr/bin/env bash
set -euo pipefail

TEXTFILE_DIR="${TEXTFILE_DIR:-/var/lib/veritasvpn/metrics}"
install -d -m 755 "$TEXTFILE_DIR"
tmp="$(mktemp "$TEXTFILE_DIR/veritas_hardware.prom.XXXXXX")"
trap 'rm -f "$tmp"' EXIT

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

if command -v fwupdmgr >/dev/null 2>&1 && command -v jq >/dev/null 2>&1; then
  firmware_updates_pending="$(fwupdmgr get-upgrades --json --no-unreported-check 2>/dev/null \
    | jq '[.Devices[]? | select(((.Releases // []) | length) > 0)] | length' 2>/dev/null || printf '0')"
  [[ "$firmware_updates_pending" =~ ^[0-9]+$ ]] || firmware_updates_pending=0
fi

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

cat > "$tmp" <<EOF
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
# HELP veritas_host_firmware_updates_pending Number of installed devices with a firmware update available.
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
  cat >> "$tmp" <<EOF
# HELP veritas_hardware_max_temperature_celsius Highest available disk or CPU temperature in Celsius.
# TYPE veritas_hardware_max_temperature_celsius gauge
veritas_hardware_max_temperature_celsius $temperature
EOF
fi
chmod 0644 "$tmp"
mv "$tmp" "$TEXTFILE_DIR/veritas_hardware.prom"
