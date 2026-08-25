# docs/

## Useful information (humans)

Project planning and design docs that are more specific than the root `IMPLEMENTATION_PLAN.md`.

| Doc | Topic |
|-----|--------|
| `BITCOIN_PAYMENTS_IMPLEMENTATION_PLAN.md` | Bitcoin-only billing via BTCPay; Free + $5 Premium |
| `ACCOUNT_DASHBOARD_IMPLEMENTATION_PLAN.md` | Logged-in Proton-like account dashboard; auth-aware CTAs |
| `MTU_STRATEGY.md` | Intentional server 1420 / client 1280 WireGuard MTU defaults |

## Useful information (AI)

- Prefer updating the focused plan in this folder when payment scope changes.
- Keep website pricing copy aligned with the “Pricing constants” section of the Bitcoin plan.
- Existing billing code lives under `services/billing-svc/` (including `internal/provider/btcpay.go`).
