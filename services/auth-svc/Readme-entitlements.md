# Auth-svc entitlement sync notes

## For humans

After Premium payment settles, billing-svc publishes NATS events. auth-svc updates
`accounts.subscription_tier` so the next JWT refresh embeds `tier=premium`.
wg-manager then allows up to 5 peers instead of 1.

## For AI

- See `internal/service/subscription_sync.go`
- Requires NATS_URL (already in docker-compose)
- Clients must refresh access token after purchase (website success page + desktop connect)
