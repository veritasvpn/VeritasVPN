#!/usr/bin/env bash
# Fast production smoke: sign-in, Premium status, EdDSA JWT, download SHA checks.
# Intended for CI (daily / workflow_dispatch / after release) — no WireGuard root needed.
set -euo pipefail

API_BASE="${API_BASE:-https://api.veritasvpn.cloud}"
SITE_BASE="${SITE_BASE:-https://veritasvpn.cloud}"
ACCOUNT_ID="${VERITAS_E2E_ACCOUNT_ID:-}"
RELEASE_TAG="${RELEASE_TAG:-}"
REQUIRE_EDDSA="${REQUIRE_EDDSA:-true}"
WORKDIR="$(mktemp -d)"
ACCESS_TOKEN=""

cleanup() {
  status=$?
  trap - EXIT INT TERM
  set +e
  if [[ -n "$ACCESS_TOKEN" ]]; then
    curl --silent --show-error --max-time 15 -X POST \
      -H "Authorization: Bearer $ACCESS_TOKEN" \
      "$API_BASE/api/v1/auth/logout-all" >/dev/null || true
  fi
  rm -rf "$WORKDIR"
  exit "$status"
}
trap cleanup EXIT INT TERM

if [[ -z "$ACCOUNT_ID" ]]; then
  printf 'VERITAS_E2E_ACCOUNT_ID is required\n' >&2
  exit 2
fi
for command in curl jq sha256sum; do
  command -v "$command" >/dev/null || { printf 'missing command: %s\n' "$command" >&2; exit 2; }
done

printf '[1/5] authenticating\n'
signin_code="$(curl --silent --show-error --max-time 20 \
  -o "$WORKDIR/signin.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg account_id "$ACCOUNT_ID" '{account_id:$account_id}')" \
  "$API_BASE/api/v1/auth/signin-account")"
if [[ "$signin_code" != "200" ]]; then
  printf 'sign-in failed with HTTP %s: %s\n' "$signin_code" "$(jq -r '.error // "unknown error"' "$WORKDIR/signin.json")" >&2
  exit 1
fi
ACCESS_TOKEN="$(jq -r '.access_token // empty' "$WORKDIR/signin.json")"
[[ -n "$ACCESS_TOKEN" ]] || { printf 'sign-in response missing access_token\n' >&2; exit 1; }

printf '[2/5] asserting Premium billing status\n'
status_code="$(curl --silent --show-error --max-time 20 \
  -o "$WORKDIR/billing.json" -w '%{http_code}' \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "$API_BASE/api/v1/billing/status")"
if [[ "$status_code" != "200" ]]; then
  printf 'billing status failed with HTTP %s\n' "$status_code" >&2
  exit 1
fi
jq -e '.is_premium == true' "$WORKDIR/billing.json" >/dev/null \
  || { printf 'expected is_premium=true: %s\n' "$(cat "$WORKDIR/billing.json")" >&2; exit 1; }

printf '[3/5] asserting access token algorithm\n'
header_b64="$(printf '%s' "$ACCESS_TOKEN" | cut -d. -f1)"
# JWT uses base64url without padding.
while (( ${#header_b64} % 4 )); do header_b64="${header_b64}="; done
header_json="$(printf '%s' "$header_b64" | tr '_-' '/+' | base64 -d 2>/dev/null || true)"
alg="$(printf '%s' "$header_json" | jq -r '.alg // empty')"
if [[ "$REQUIRE_EDDSA" == "true" ]]; then
  [[ "$alg" == "EdDSA" ]] || { printf 'expected alg=EdDSA, got %q\n' "$alg" >&2; exit 1; }
else
  [[ "$alg" == "EdDSA" || "$alg" == "HS256" ]] || { printf 'unexpected alg %q\n' "$alg" >&2; exit 1; }
fi
printf '  alg=%s kid=%s\n' "$alg" "$(printf '%s' "$header_json" | jq -r '.kid // empty')"

printf '[4/5] resolving release tag and SHA256SUMS\n'
if [[ -z "$RELEASE_TAG" ]]; then
  RELEASE_TAG="$(curl --silent --show-error --max-time 20 \
    -H 'Accept: application/vnd.github+json' \
    https://api.github.com/repos/veritasvpn/VeritasVPN/releases/latest \
    | jq -r '.tag_name // empty')"
fi
[[ -n "$RELEASE_TAG" && "$RELEASE_TAG" != "null" ]] \
  || { printf 'could not resolve RELEASE_TAG\n' >&2; exit 1; }
sums_url="https://github.com/veritasvpn/VeritasVPN/releases/download/${RELEASE_TAG}/SHA256SUMS"
curl --fail --silent --show-error --max-time 60 -L -o "$WORKDIR/SHA256SUMS" "$sums_url"

printf '[5/5] verifying GitHub release asset digests (+ optional live download sample)\n'
# Verify APK from GitHub against SHA256SUMS (present on every client release).
apk_name="veritasvpn-android.apk"
grep -E "[[:space:]]${apk_name}\$" "$WORKDIR/SHA256SUMS" >"$WORKDIR/apk.sum" \
  || { printf 'SHA256SUMS missing %s\n' "$apk_name" >&2; exit 1; }
curl --fail --silent --show-error --max-time 180 -L \
  -o "$WORKDIR/$apk_name" \
  "https://github.com/veritasvpn/VeritasVPN/releases/download/${RELEASE_TAG}/${apk_name}"
( cd "$WORKDIR" && sha256sum --check apk.sum )

# Live site Function (cache-bust) — compare digest to the same SUMS line.
live_url="${SITE_BASE}/downloads/${apk_name}?cb=$(date +%s)"
curl --fail --silent --show-error --max-time 180 -L -o "$WORKDIR/live-$apk_name" "$live_url"
expected="$(awk '{print $1}' "$WORKDIR/apk.sum")"
actual="$(sha256sum "$WORKDIR/live-$apk_name" | awk '{print $1}')"
if [[ "$expected" != "$actual" ]]; then
  printf 'live download digest mismatch for %s\n  expected %s\n  actual   %s\n' \
    "$apk_name" "$expected" "$actual" >&2
  exit 1
fi

printf 'Production API + download smoke: PASS (tag=%s)\n' "$RELEASE_TAG"
