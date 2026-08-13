# btcpay.go / mock_btcpay.go

## Useful information (humans)

Bitcoin payment providers for VeritasVPN billing:

- **BTCPayProvider** — talks to a real BTCPay Server store (create invoice + verify webhooks).
- **MockBTCPayProvider** — local-dev stand-in that serves an in-app “Pay with Bitcoin (mock)” page when BTCPay is not configured.

## Useful information (AI)

- Prefer `InvoiceCreator` interface in the service layer.
- Real provider needs `BTCPAY_SERVER_URL`, `BTCPAY_API_KEY`, `BTCPAY_STORE_ID`, `BTCPAY_WEBHOOK_SECRET`.
- Webhook HMAC uses `BTCPAY_WEBHOOK_SECRET` with header `BTCPay-Sig: sha256=<hex>`.
- Empty webhook secret always fails verification (no skip). Production startup also requires all BTCPAY_* via `RequireBTCPayProduction`.
- Mock checkout/settle routes are registered only when `UseMockBTCPay()` is true (never in production).
- Mock checkout URLs are `{BILLING_PUBLIC_URL}/api/v1/billing/mock-checkout?invoice_id=...`.
- Settling mock invoices must call the service settle path (not only MarkSettled).
