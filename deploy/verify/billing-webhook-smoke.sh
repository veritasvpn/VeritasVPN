#!/usr/bin/env bash
# HMAC-signed BTCPay InvoiceSettled smoke (no mock billing, no mainnet payment).
# Dell/ops only: needs BTCPAY_WEBHOOK_SECRET and a non-Premium (or cancelable) account.
#
# Flow:
#   1) sign-in
#   2) if already Premium without cancel_at_period_end → cancel (keeps access until period end)
#   3) subscribe → create real BTCPay invoice + pending payment row
#   4) POST signed InvoiceSettled webhook
#   5) assert is_premium
#
# Optional: SMOKE_INVOICE_ID=... skips subscribe and settles that pending invoice.
set -euo pipefail

API_BASE="${API_BASE:-https://api.veritasvpn.cloud}"
ACCOUNT_ID="${VERITAS_WEBHOOK_SMOKE_ACCOUNT_ID:-${VERITAS_E2E_ACCOUNT_ID:-}}"
WEBHOOK_SECRET="${BTCPAY_WEBHOOK_SECRET:-}"
SMOKE_INVOICE_ID="${SMOKE_INVOICE_ID:-}"
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
  printf 'VERITAS_WEBHOOK_SMOKE_ACCOUNT_ID (or VERITAS_E2E_ACCOUNT_ID) is required\n' >&2
  exit 2
fi
if [[ -z "$WEBHOOK_SECRET" ]]; then
  printf 'BTCPAY_WEBHOOK_SECRET is required (never enable ALLOW_MOCK_BTCPAY in production)\n' >&2
  exit 2
fi
for command in curl jq openssl; do
  command -v "$command" >/dev/null || { printf 'missing command: %s\n' "$command" >&2; exit 2; }
done

printf '[1/5] authenticating\n'
signin_code="$(curl --silent --show-error --max-time 20 \
  -o "$WORKDIR/signin.json" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg account_id "$ACCOUNT_ID" '{account_id:$account_id}')" \
  "$API_BASE/api/v1/auth/signin-account")"
[[ "$signin_code" == "200" ]] || { printf 'sign-in failed HTTP %s\n' "$signin_code" >&2; exit 1; }
ACCESS_TOKEN="$(jq -r '.access_token // empty' "$WORKDIR/signin.json")"
[[ -n "$ACCESS_TOKEN" ]] || exit 1

invoice_id="$SMOKE_INVOICE_ID"
if [[ -z "$invoice_id" ]]; then
  printf '[2/5] ensuring checkout can be created\n'
  curl --silent --show-error --max-time 20 \
    -o "$WORKDIR/status0.json" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    "$API_BASE/api/v1/billing/status" >/dev/null
  if jq -e '.is_premium == true and .cancel_at_period_end != true' "$WORKDIR/status0.json" >/dev/null; then
    printf '  Premium active — setting cancel_at_period_end so renew checkout is allowed\n'
    cancel_code="$(curl --silent --show-error --max-time 20 \
      -o "$WORKDIR/cancel.json" -w '%{http_code}' -X POST \
      -H "Authorization: Bearer $ACCESS_TOKEN" \
      "$API_BASE/api/v1/billing/cancel")"
    [[ "$cancel_code" == "200" ]] || { printf 'cancel failed HTTP %s\n' "$cancel_code" >&2; exit 1; }
  fi

  printf '[3/5] creating BTCPay checkout (pending payment)\n'
  sub_code="$(curl --silent --show-error --max-time 45 \
    -o "$WORKDIR/subscribe.json" -w '%{http_code}' -X POST \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    --data '{"tier":"premium","payment_method":"btcpay","plan_id":"premium_monthly"}' \
    "$API_BASE/api/v1/billing/subscribe")"
  if [[ "$sub_code" != "201" ]]; then
    printf 'subscribe failed HTTP %s: %s\n' "$sub_code" "$(jq -r '.error // .' "$WORKDIR/subscribe.json")" >&2
    exit 1
  fi
  checkout_url="$(jq -r '.checkout_url // empty' "$WORKDIR/subscribe.json")"
  [[ -n "$checkout_url" ]] || { printf 'missing checkout_url\n' >&2; exit 1; }
  # BTCPay links look like .../i/<invoiceId> or .../invoice/<id>
  invoice_id="$(printf '%s' "$checkout_url" | sed -n 's|.*/i/\([^/?#]*\).*|\1|p')"
  if [[ -z "$invoice_id" ]]; then
    invoice_id="$(printf '%s' "$checkout_url" | sed -n 's|.*/invoice/\([^/?#]*\).*|\1|p')"
  fi
  [[ -n "$invoice_id" ]] || { printf 'could not parse invoice id from %s\n' "$checkout_url" >&2; exit 1; }
else
  printf '[2/5] using SMOKE_INVOICE_ID=%s\n' "$invoice_id"
  printf '[3/5] skipped subscribe\n'
fi

printf '[4/5] posting signed InvoiceSettled webhook\n'
payload="$(jq -nc \
  --arg type InvoiceSettled \
  --arg invoiceId "$invoice_id" \
  --arg account_id "$ACCOUNT_ID" \
  '{type:$type,invoiceId:$invoiceId,metadata:{account_id:$account_id,tier:"premium",plan_id:"premium_monthly"}}')"
printf '%s' "$payload" >"$WORKDIR/payload.json"
sig_hex="$(printf '%s' "$payload" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | awk '{print $NF}')"
wh_code="$(curl --silent --show-error --max-time 30 \
  -o "$WORKDIR/webhook.json" -w '%{http_code}' -X POST \
  -H 'Content-Type: application/json' \
  -H "BTCPay-Sig: sha256=${sig_hex}" \
  --data @"$WORKDIR/payload.json" \
  "$API_BASE/api/v1/billing/webhook/btcpay")"
if [[ "$wh_code" != "200" && "$wh_code" != "204" ]]; then
  printf 'webhook failed HTTP %s: %s\n' "$wh_code" "$(cat "$WORKDIR/webhook.json")" >&2
  exit 1
fi

printf '[5/5] asserting Premium\n'
sleep 1
curl --silent --show-error --max-time 20 \
  -o "$WORKDIR/status1.json" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  "$API_BASE/api/v1/billing/status" >/dev/null
jq -e '.is_premium == true' "$WORKDIR/status1.json" >/dev/null \
  || { printf 'expected premium after settle: %s\n' "$(cat "$WORKDIR/status1.json")" >&2; exit 1; }

printf 'Billing webhook smoke: PASS (invoice=%s)\n' "$invoice_id"
