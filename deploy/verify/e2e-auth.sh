#!/usr/bin/env bash
# Shared signed authentication for synthetic production checks.
# The signature covers one account and a two-minute server-side time window.
e2e_auth_init() {
  local account_id="$1"
  : "${VERITAS_E2E_AUTH_SECRET:?VERITAS_E2E_AUTH_SECRET is required}"
  command -v openssl >/dev/null || { echo "missing command: openssl" >&2; return 2; }
  E2E_AUTH_TIMESTAMP="$(date +%s)"
  E2E_AUTH_SIGNATURE="$(
    printf '%s\n%s' "$E2E_AUTH_TIMESTAMP" "$account_id" |
      openssl dgst -sha256 -hmac "$VERITAS_E2E_AUTH_SECRET" -r |
      awk '{print $1}'
  )"
  [[ "$E2E_AUTH_SIGNATURE" =~ ^[0-9a-f]{64}$ ]]
  export E2E_AUTH_TIMESTAMP E2E_AUTH_SIGNATURE
}
