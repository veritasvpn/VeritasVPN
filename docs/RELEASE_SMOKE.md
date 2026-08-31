# Release smoke checklist

Run after every client or backend production release. Automations cover most of this; keep the manual items for Android/Linux UX and real Bitcoin when needed.

## Automated

| Check | How |
|-------|-----|
| Sign-in + Premium + EdDSA JWT + download SHA | `.github/workflows/prod-smoke.yml` → `deploy/verify/prod-smoke-api.sh` (daily + release + dispatch) |
| Short WireGuard connect | `.github/workflows/vpn-e2e.yml` → `deploy/verify/external-wireguard-e2e.sh` (hourly) |
| 5+ min tunnel, no 2m flap | Dell cron → `deploy/verify/tunnel-hold-e2e.sh` (root; `HOLD_SECONDS=360`) |
| BTCPay webhook → Premium | Dell → `BTCPAY_WEBHOOK_SECRET=… deploy/verify/billing-webhook-smoke.sh` (HMAC settle; **never** `ALLOW_MOCK_BTCPAY` in prod) |

### Dell cron example (tunnel hold)

```cron
15 4 * * * root VERITAS_E2E_ACCOUNT_ID=… /home/jpg/VeritasVPN/deploy/verify/tunnel-hold-e2e.sh >>/var/log/veritas-tunnel-hold.log 2>&1
```

### Webhook smoke notes

- Prefer a dedicated free/smoke account via `VERITAS_WEBHOOK_SMOKE_ACCOUNT_ID`.
- If the account is already Premium, the script cancels-at-period-end first so renew checkout works, then settles the new invoice (extends Premium).
- Real mainnet sat payment remains an optional manual release checkbox.

## Per-release human checklist

- [ ] CI release workflow green; GitHub assets + `SHA256SUMS` present
- [ ] `prod-smoke` workflow green (or run `deploy/verify/prod-smoke-api.sh` locally)
- [ ] Install pages / Functions point at the new tag; SHAs match release
- [ ] Pages deploy green
- [ ] Manual: Android + Linux connect ≥5 minutes
- [ ] Manual **or** `billing-webhook-smoke.sh`: Bitcoin settle → Premium
- [ ] After JWT cutover: no workload mounts `JWT_SECRET` (`kubectl -n veritas get deploy -o yaml | grep JWT_SECRET` empty)

## Related

- Plan: `docs/PRE_PROD_JWT_ALWAYS_ON_SMOKE_PLAN.md`
- Deploy path: `docs/DEPLOYMENT_SOURCE_OF_TRUTH.md`
