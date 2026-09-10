#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${BTCPAY_NAMESPACE:-btcpay-mainnet}"
PUBLIC_URL="${BTCPAY_PUBLIC_URL:-https://btcpay-mainnet.veritasvpn.cloud}"
KUBECTL="${KUBECTL:-kubectl}"

pod="$($KUBECTL -n "$NAMESPACE" get pods -l app=postgres-btcpay-mainnet -o jsonpath='{.items[0].metadata.name}')"
if [[ -z "$pod" ]]; then
  printf 'FAIL: no BTCPay PostgreSQL pod found in %s\n' "$NAMESPACE" >&2
  exit 1
fi

counts="$($KUBECTL -n "$NAMESPACE" exec "$pod" -- sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc '\''SELECT count(*), count(*) FILTER (WHERE "TwoFactorEnabled") FROM "AspNetUsers";'\''')"
IFS='|' read -r users mfa_enabled <<<"$counts"
if [[ ! "$users" =~ ^[0-9]+$ || ! "$mfa_enabled" =~ ^[0-9]+$ ]]; then
  printf 'FAIL: could not parse BTCPay administrator MFA counts\n' >&2
  exit 1
fi
if (( users == 0 )); then
  printf 'FAIL: BTCPay has no administrator account\n' >&2
  exit 1
fi
if (( mfa_enabled != users )); then
  printf 'FAIL: BTCPay MFA is enabled for %d of %d accounts\n' "$mfa_enabled" "$users" >&2
  exit 1
fi

register_status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "$PUBLIC_URL/register")"
if [[ ! "$register_status" =~ ^30[12378]$ ]]; then
  printf 'FAIL: BTCPay registration endpoint returned HTTP %s (expected redirect)\n' "$register_status" >&2
  exit 1
fi

printf 'PASS: BTCPay MFA is enabled for all %d accounts and public registration is disabled\n' "$users"
