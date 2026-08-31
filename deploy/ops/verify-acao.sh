#!/usr/bin/env bash
# Verify marketing/site responses do not advertise Access-Control-Allow-Origin: *
set -euo pipefail

BASES=(
  "${SITE_BASE:-https://veritasvpn.cloud}"
  "${SITE_WWW:-https://www.veritasvpn.cloud}"
)
PATHS=(
  /
  /install/android.html
  /install/linux.html
  /account/
)

fail=0
for base in "${BASES[@]}"; do
  for path in "${PATHS[@]}"; do
    url="${base}${path}"
    headers="$(curl -sI --max-time 20 -L "$url" | tr -d '\r' || true)"
    acao="$(printf '%s\n' "$headers" | awk -F': ' 'tolower($1)=="access-control-allow-origin"{print tolower($2); exit}')"
    if [[ -z "$acao" ]]; then
      printf 'OK  %s (no ACAO)\n' "$url"
      continue
    fi
    if [[ "$acao" == "*" ]]; then
      printf 'FAIL %s ACAO=*\n' "$url" >&2
      fail=1
    else
      printf 'WARN %s ACAO=%s (not *; review if intentional)\n' "$url" "$acao"
    fi
  done
done

if (( fail == 1 )); then
  printf '\nFix: Cloudflare Transform Rule → remove Access-Control-Allow-Origin for the zone.\n' >&2
  printf 'Also keep website/_headers "! Access-Control-Allow-Origin".\n' >&2
  exit 1
fi
printf 'ACAO check: PASS\n'
