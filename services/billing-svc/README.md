# billing-svc

## Useful information (humans)

HTTP billing service for VeritasVPN.

- **Free** plan on Firebase sign-in (ensured via `/status`)
- **Premium** ($3 / 30 days) paid with **Bitcoin** via BTCPay Server
- Local **mock checkout** when `BTCPAY_API_KEY` is unset (no real BTC required)

Auth: Firebase ID token (`Authorization: Bearer <idToken>`). Account ID = Firebase UID.

## Useful information (AI)

- Entry: `cmd/server/main.go`
- Routes: subscribe, cancel, status, webhook/btcpay, google-play/verify, webhook/google-play, mock-checkout, mock-settle, healthz
- Google Play verification stays disabled until `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` is set. See `docs/play-billing.md`.
- Mock mode: `config.UseMockBTCPay()` when not production and BTCPay not configured
- Migrations: `migrations/001_initial.sql` (docker init) + embedded `002` via `internal/migrate`
- Website client: `website/js/billing.js`
- Plan doc: `docs/BITCOIN_PAYMENTS_IMPLEMENTATION_PLAN.md`
