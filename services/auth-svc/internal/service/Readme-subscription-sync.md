# Subscription sync (auth-svc)

## For humans

auth-svc listens on NATS for billing events and updates `accounts.subscription_tier`
(+ expiry) so the next access-token refresh embeds the correct `tier` claim for
wg-manager entitlements.

| Subject | Effect |
|---------|--------|
| `subscription.renewed` | tier=premium, expiry=period_end |
| `subscription.expired` | tier=free, clear expiry |
| `subscription.canceled` | ignored (premium until period ends) |

## For AI

- File: `subscription_sync.go` — `AuthService.StartSubscriptionSync`
- Requires `NATS_URL` and billing-svc publishing the same subjects
- After Premium purchase, clients should refresh the access token (or wait for TTL)
- Do not demote on `canceled` — wait for `expired`
