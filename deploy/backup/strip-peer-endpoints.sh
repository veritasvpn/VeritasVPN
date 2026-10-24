#!/usr/bin/env bash
# Remove WireGuard peer endpoints from text that would otherwise be archived.
# An endpoint is the client's real public address. Interface config, the server
# private key, peer public keys, preshared keys, and allowed IPs are kept.
# This file is safe to execute (stdin filter, or --check DIR) or to source.
set -euo pipefail

strip_peer_endpoints() {
  awk '
    /^[[:space:]]*[Ee]ndpoint[[:space:]]*[:=]/ { next }
    {
      n = split($0, f, /[ \t]+/)
      if (n >= 8 && f[4] ~ /\// && f[5] ~ /^[0-9]+$/ && f[3] ~ /^(\(none\)|[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+|\[[0-9A-Fa-f:]+\]:[0-9]+)$/) {
        f[3] = "(none)"
        printf "%s", f[1]
        for (i = 2; i <= n; i++) printf " %s", f[i]
        printf "\n"
        next
      }
      print
    }
  '
}

# Fail if a directory holds a WireGuard peer endpoint. Kubernetes manifests
# name their own keys (MANAGER_ENDPOINT, PUBLIC_IP) and are not this pattern.
assert_no_peer_endpoints_in_tree() {
  local root="$1"
  local hits files
  hits="$(grep -rInI -E \
    -e '^[[:space:]]*[Ee]ndpoint[[:space:]]*[:=][[:space:]]*[^[:space:]]+:[0-9]+' \
    -e '(^|[[:space:]])([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]+[[:space:]]+[0-9A-Fa-f:.]+/' \
    -e '(^|[[:space:]])\[[0-9A-Fa-f:]+\]:[0-9]+[[:space:]]+[0-9A-Fa-f:.]+/' \
    "$root" || true)"
  if [[ -n "$hits" ]]; then
    echo "[backup] ERROR: refusing to archive a WireGuard peer endpoint" >&2
    files="$(printf '%s\n' "$hits" | cut -d: -f1 | sort -u)"
    printf '[backup] ERROR: endpoint in %s\n' "$files" >&2
    return 1
  fi
}

# Drop Endpoint lines from copied WireGuard config text. Private-key files
# are not rewritten.
sanitize_wireguard_tree() {
  local dir="$1"
  local file tmp
  [[ -d "$dir" ]] || return 0
  while IFS= read -r -d '' file; do
    case "$file" in
      *.key|*.pem|*.p12|*.gpg|*.gz|*.hmac|*.sha256) continue ;;
    esac
    tmp="$(mktemp)"
    strip_peer_endpoints < "$file" > "$tmp"
    cat "$tmp" > "$file"
    rm -f "$tmp"
  done < <(find "$dir" -type f -print0)
}

capture_wg_showconf() {
  local iface="$1"
  local dest="$2"
  wg showconf "$iface" | strip_peer_endpoints > "$dest"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  case "${1:-}" in
    --check)
      assert_no_peer_endpoints_in_tree "${2:?directory required}"
      ;;
    *)
      strip_peer_endpoints
      ;;
  esac
fi
