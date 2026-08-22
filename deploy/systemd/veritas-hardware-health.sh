#!/usr/bin/env bash
set -euo pipefail

TEXTFILE_DIR="${TEXTFILE_DIR:-/var/lib/veritasvpn/metrics}"
install -d -m 755 "$TEXTFILE_DIR"
tmp="$(mktemp "$TEXTFILE_DIR/veritas_hardware.prom.XXXXXX")"
trap 'rm -f "$tmp"' EXIT

smart_available=0
smart_healthy=1
temperature=""

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

cat > "$tmp" <<EOF
# HELP veritas_hardware_smart_available SMART telemetry availability.
# TYPE veritas_hardware_smart_available gauge
veritas_hardware_smart_available $smart_available
# HELP veritas_hardware_smart_healthy Whether all detected SMART-capable disks report healthy.
# TYPE veritas_hardware_smart_healthy gauge
veritas_hardware_smart_healthy $smart_healthy
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
