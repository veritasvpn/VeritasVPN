#!/usr/bin/env bash
# Parser tests for veritas-hardware-health firmware metrics (fwupdmgr --json).
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SCRIPT="$ROOT/deploy/systemd/veritas-hardware-health.sh"
DEFAULT_IGNORE="$ROOT/deploy/systemd/firmware-ignore.conf"
DBX_ID="362301da643102b9f38477387e2193e57abaa590"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

assert_eq() {
  local got="$1"
  local want="$2"
  local message="$3"
  if [[ "$got" != "$want" ]]; then
    printf 'FAIL: %s\n expected: [%s]\n got:      [%s]\n' "$message" "$want" "$got" >&2
    exit 1
  fi
}

assert_line() {
  local file="$1"
  local line="$2"
  local message="$3"
  if ! grep -Fxq -- "$line" "$file"; then
    printf 'FAIL: %s\n missing: %s\n----\n%s\n----\n' "$message" "$line" "$(cat "$file")" >&2
    exit 1
  fi
}

assert_absent() {
  local file="$1"
  local needle="$2"
  local message="$3"
  if grep -F -- "$needle" "$file" >/dev/null; then
    printf 'FAIL: %s\n found: %s\n----\n%s\n----\n' "$message" "$needle" "$(cat "$file")" >&2
    exit 1
  fi
}

command -v jq >/dev/null 2>&1 || fail "jq is required"

# shellcheck source=veritas-hardware-health.sh
source "$SCRIPT"

tmpdir="$(mktemp -d)"
cleanup() { rm -rf "$tmpdir"; }
trap cleanup EXIT

grep -q "${DBX_ID} 20260707" "$DEFAULT_IGNORE" || fail "default ignore file is missing the pinned dbx release"
grep -q 'expected 20260707 and got 20230501' "$DEFAULT_IGNORE" || fail "default ignore file does not say why the dbx release is pinned"
grep -q 'bios_minimum 1.32.0' "$DEFAULT_IGNORE" || fail "default ignore file is missing bios_minimum 1.32.0"

cat >"$tmpdir/upgrades.json" <<EOF
{
  "Devices": [
    {
      "Name": "UEFI dbx",
      "DeviceId": "${DBX_ID}",
      "Version": "20230501",
      "Serial": "SN-DO-NOT-LEAK",
      "Guid": ["GUID-DO-NOT-EMIT"],
      "Hostname": "host-do-not-emit",
      "Releases": [
        {"Version": "20230501"},
        {"Version": "20260707"}
      ]
    },
    {
      "Name": "TPM",
      "DeviceId": "1111111111111111111111111111111111111111",
      "Version": "1.0",
      "Releases": [
        {"Version": "1.2"},
        {"Version": "1.10"}
      ]
    },
    {
      "Name": "No update",
      "DeviceId": "2222222222222222222222222222222222222222",
      "Version": "9",
      "Serial": "SN-ALSO-HIDDEN",
      "Releases": []
    }
  ]
}
EOF

render_firmware_metrics "$tmpdir/upgrades.json" "$DEFAULT_IGNORE" "1.26.0"
assert_eq "$FIRMWARE_UPDATES_PENDING" "1" "pinned dbx release is excluded and TPM still counts"
detail="$tmpdir/detail.prom"
printf '%s\n' "$FIRMWARE_DETAIL_METRICS" >"$detail"
assert_line "$detail" "veritas_host_firmware_update_available{device=\"UEFI dbx\",current=\"20230501\",available=\"20260707\",ignored=\"true\"} 1" "dbx series is ignored at the pinned release"
assert_line "$detail" "veritas_host_firmware_update_available{device=\"TPM\",current=\"1.0\",available=\"1.10\",ignored=\"false\"} 1" "newest numeric TPM release is the offered version"
assert_absent "$detail" "No update" "devices without a release are omitted"
assert_absent "$detail" "SN-DO-NOT-LEAK" "serials are not labels"
assert_absent "$detail" "SN-ALSO-HIDDEN" "serials on devices without updates are not labels"
assert_absent "$detail" "GUID-DO-NOT-EMIT" "guids are not labels"
assert_absent "$detail" "host-do-not-emit" "host names from fwupd JSON are not labels"
assert_absent "$detail" "$DBX_ID" "fwupd device ids are not labels"
bios="$tmpdir/bios.prom"
printf '%s\n' "$BIOS_METRICS" >"$bios"
assert_line "$bios" "veritas_host_bios_below_minimum{version=\"1.26.0\",minimum=\"1.32.0\"} 1" "BIOS 1.26.0 is below 1.32.0"

render_firmware_metrics "$tmpdir/upgrades.json" "$DEFAULT_IGNORE" "1.32.0"
assert_eq "$FIRMWARE_UPDATES_PENDING" "1" "BIOS comparison does not change the firmware count"
printf '%s\n' "$BIOS_METRICS" >"$bios"
assert_line "$bios" "veritas_host_bios_below_minimum{version=\"1.32.0\",minimum=\"1.32.0\"} 0" "BIOS equal to the minimum is not below it"

render_firmware_metrics "$tmpdir/upgrades.json" "$DEFAULT_IGNORE" "1.33.0"
printf '%s\n' "$BIOS_METRICS" >"$bios"
assert_line "$bios" "veritas_host_bios_below_minimum{version=\"1.33.0\",minimum=\"1.32.0\"} 0" "BIOS newer than the minimum is not below it"

render_firmware_metrics "$tmpdir/upgrades.json" "$DEFAULT_IGNORE" "1.8.0"
printf '%s\n' "$BIOS_METRICS" >"$tmpdir/bios-1.8.prom"
# 1.8.0 is older than 1.32.0 when components are compared as integers.
assert_line "$tmpdir/bios-1.8.prom" "veritas_host_bios_below_minimum{version=\"1.8.0\",minimum=\"1.32.0\"} 1" "1.8.0 is below 1.32.0"

render_firmware_metrics "$tmpdir/upgrades.json" "$DEFAULT_IGNORE" "1.100.0"
printf '%s\n' "$BIOS_METRICS" >"$tmpdir/bios-1.100.prom"
assert_line "$tmpdir/bios-1.100.prom" "veritas_host_bios_below_minimum{version=\"1.100.0\",minimum=\"1.32.0\"} 0" "1.100.0 is not below 1.32.0"

render_firmware_metrics "$tmpdir/upgrades.json" "$DEFAULT_IGNORE" "A20"
assert_eq "$BIOS_METRICS" "" "unparseable BIOS versions do not raise the minimum alert"

cat >"$tmpdir/newer-dbx.json" <<EOF
{
  "Devices": [
    {
      "Name": "UEFI dbx",
      "DeviceId": "${DBX_ID}",
      "Version": "20230501",
      "Releases": [{"Version": "20270101"}, {"Version": "20260707"}]
    }
  ]
}
EOF
render_firmware_metrics "$tmpdir/newer-dbx.json" "$DEFAULT_IGNORE" ""
assert_eq "$FIRMWARE_UPDATES_PENDING" "1" "a newer dbx release than the pin still alerts"
printf '%s\n' "$FIRMWARE_DETAIL_METRICS" >"$tmpdir/newer.prom"
assert_line "$tmpdir/newer.prom" "veritas_host_firmware_update_available{device=\"UEFI dbx\",current=\"20230501\",available=\"20270101\",ignored=\"false\"} 1" "newer dbx release is not ignored"
assert_eq "$BIOS_METRICS" "" "empty installed BIOS emits no BIOS series"

cat >"$tmpdir/custom.conf" <<'EOF'
# ignored entirely, whatever fwupd offers
abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd

# only this offered version
eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee 2.0

bios_minimum 1.26.0 # inline comment
EOF
cat >"$tmpdir/custom.json" <<'EOF'
{
  "Devices": [
    {
      "Name": "Any version",
      "DeviceId": "abcdabcdabcdabcdabcdabcdabcdabcdabcdabcd",
      "Version": "1",
      "Releases": [{"Version": "9"}]
    },
    {
      "Name": "Pinned old",
      "DeviceId": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
      "Version": "1.0",
      "Releases": [{"Version": "2.0"}]
    },
    {
      "Name": "Pinned old",
      "DeviceId": "ffffffffffffffffffffffffffffffffffffffff",
      "Version": "1.0",
      "Releases": [{"Version": "2.1"}]
    }
  ]
}
EOF
render_firmware_metrics "$tmpdir/custom.json" "$tmpdir/custom.conf" "1.8.0"
assert_eq "$FIRMWARE_UPDATES_PENDING" "1" "unpinned device id ignores every release; a different id still counts"
printf '%s\n' "$FIRMWARE_DETAIL_METRICS" >"$tmpdir/custom.prom"
assert_line "$tmpdir/custom.prom" "veritas_host_firmware_update_available{device=\"Any version\",current=\"1\",available=\"9\",ignored=\"true\"} 1" "device id without a version ignores every release"
assert_line "$tmpdir/custom.prom" "veritas_host_firmware_update_available{device=\"Pinned old\",current=\"1.0\",available=\"2.0\",ignored=\"true\"} 1" "exact offered version is ignored"
assert_line "$tmpdir/custom.prom" "veritas_host_firmware_update_available{device=\"Pinned old\",current=\"1.0\",available=\"2.1\",ignored=\"false\"} 1" "a different device id is not covered by the version pin"
printf '%s\n' "$BIOS_METRICS" >"$tmpdir/custom-bios.prom"
assert_line "$tmpdir/custom-bios.prom" "veritas_host_bios_below_minimum{version=\"1.8.0\",minimum=\"1.26.0\"} 1" "inline bios_minimum comment is ignored and 1.8.0 is below 1.26.0"

cat >"$tmpdir/weird.json" <<'EOF'
{
  "Devices": [
    {
      "Name": "Weird \"Name\" \\ X",
      "DeviceId": "3333333333333333333333333333333333333333",
      "Version": "1",
      "Releases": [{"Version": "2"}]
    },
    {
      "Name": "Line\nBreak",
      "DeviceId": "4444444444444444444444444444444444444444",
      "Version": "3",
      "Releases": [{"Version": "abc"}, {"Version": "4"}]
    }
  ]
}
EOF
printf '\n\n# nothing\n' >"$tmpdir/empty.conf"
render_firmware_metrics "$tmpdir/weird.json" "$tmpdir/empty.conf" ""
assert_eq "$FIRMWARE_UPDATES_PENDING" "2" "unlisted devices count"
printf '%s\n' "$FIRMWARE_DETAIL_METRICS" >"$tmpdir/weird.prom"
assert_line "$tmpdir/weird.prom" "veritas_host_firmware_update_available{device=\"Weird \\\"Name\\\" \\\\ X\",current=\"1\",available=\"2\",ignored=\"false\"} 1" "label values escape quotes and backslashes"
assert_line "$tmpdir/weird.prom" "veritas_host_firmware_update_available{device=\"Line Break\",current=\"3\",available=\"4\",ignored=\"false\"} 1" "newest numeric release wins over a non-numeric release"

cat >"$tmpdir/duplicates.json" <<'EOF'
{
  "Devices": [
    {
      "Name": "Same",
      "DeviceId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "Version": "1",
      "Releases": [{"Version": "2"}]
    },
    {
      "Name": "Same",
      "DeviceId": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      "Version": "1",
      "Releases": [{"Version": "2"}]
    }
  ]
}
EOF
render_firmware_metrics "$tmpdir/duplicates.json" "$tmpdir/empty.conf" ""
assert_eq "$FIRMWARE_UPDATES_PENDING" "2" "duplicate device names both count"
printf '%s\n' "$FIRMWARE_DETAIL_METRICS" >"$tmpdir/duplicates.prom"
assert_line "$tmpdir/duplicates.prom" "veritas_host_firmware_update_available{device=\"Same\",current=\"1\",available=\"2\",ignored=\"false\"} 1" "first duplicate keeps the device name"
assert_line "$tmpdir/duplicates.prom" "veritas_host_firmware_update_available{device=\"Same (2)\",current=\"1\",available=\"2\",ignored=\"false\"} 1" "duplicate device names stay unique series"

printf 'not-json' >"$tmpdir/bad.json"
render_firmware_metrics "$tmpdir/bad.json" "$DEFAULT_IGNORE" "1.26.0"
assert_eq "$FIRMWARE_UPDATES_PENDING" "0" "invalid fwupdmgr JSON counts as no pending updates"
assert_eq "$FIRMWARE_DETAIL_METRICS" "" "invalid fwupdmgr JSON emits no per-device series"
printf '%s\n' "$BIOS_METRICS" >"$tmpdir/bad-bios.prom"
assert_line "$tmpdir/bad-bios.prom" "veritas_host_bios_below_minimum{version=\"1.26.0\",minimum=\"1.32.0\"} 1" "BIOS check still runs when fwupdmgr JSON is invalid"

render_firmware_metrics "$tmpdir/upgrades.json" "$tmpdir/missing-ignore.conf" "1.26.0"
assert_eq "$FIRMWARE_UPDATES_PENDING" "2" "missing ignore file counts every offered update"
printf '%s\n' "$FIRMWARE_DETAIL_METRICS" >"$tmpdir/missing-ignore.prom"
assert_line "$tmpdir/missing-ignore.prom" "veritas_host_firmware_update_available{device=\"UEFI dbx\",current=\"20230501\",available=\"20260707\",ignored=\"false\"} 1" "missing ignore file does not hide dbx"
assert_eq "$BIOS_METRICS" "" "missing ignore file does not invent a BIOS minimum"

# Full script: fake fwupdmgr must be invoked with --json, and the textfile
# must carry the same series the parser produced above.
bin="$tmpdir/bin"
mkdir -p "$bin"
cat >"$bin/fwupdmgr" <<'EOF'
#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >"${FWUPDMGR_ARGS_FILE:?}"
case " $* " in
  *" --json "*) ;;
  *) printf 'missing --json\n' >&2; exit 2 ;;
esac
cat "${FWUPDMGR_FIXTURE:?}"
EOF
for stub in curl apt-get systemctl sensors smartctl dmidecode; do
  cat >"$bin/$stub" <<'EOF'
#!/bin/sh
exit 0
EOF
done
cat >"$bin/lsblk" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod 0755 "$bin"/*
printf '1.26.0\n' >"$tmpdir/bios-version"
mkdir -p "$tmpdir/metrics"

PATH="$bin:/usr/bin:/bin" \
  TEXTFILE_DIR="$tmpdir/metrics" \
  FIRMWARE_IGNORE_FILE="$DEFAULT_IGNORE" \
  BIOS_VERSION_FILE="$tmpdir/bios-version" \
  FWUPDMGR_FIXTURE="$tmpdir/upgrades.json" \
  FWUPDMGR_ARGS_FILE="$tmpdir/fwupdmgr-args" \
  bash "$SCRIPT"

assert_eq "$(cat "$tmpdir/fwupdmgr-args")" "get-upgrades --json --no-unreported-check" "fwupdmgr is called with --json"
prom="$tmpdir/metrics/veritas_hardware.prom"
assert_line "$prom" "veritas_host_firmware_updates_pending 1" "textfile pending count excludes the pinned dbx release"
assert_line "$prom" "veritas_host_firmware_update_available{device=\"UEFI dbx\",current=\"20230501\",available=\"20260707\",ignored=\"true\"} 1" "textfile records the ignored dbx release"
assert_line "$prom" "veritas_host_firmware_update_available{device=\"TPM\",current=\"1.0\",available=\"1.10\",ignored=\"false\"} 1" "textfile records the pending TPM release"
assert_line "$prom" "veritas_host_bios_below_minimum{version=\"1.26.0\",minimum=\"1.32.0\"} 1" "textfile records BIOS below the configured minimum"
assert_absent "$prom" "SN-DO-NOT-LEAK" "textfile does not contain a serial"
assert_absent "$prom" "$DBX_ID" "textfile does not contain a fwupd device id"
test "$(stat -c %a "$prom")" = "644" || fail "metrics file mode is not 0644"

if command -v promtool >/dev/null 2>&1; then
  promtool check rules "$ROOT/deploy/k8s/monitoring/alerts.yml" || fail "promtool check rules failed"
fi

printf 'ok\n'
