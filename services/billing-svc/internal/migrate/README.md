# migrate

## Useful information (humans)

Applies idempotent SQL patches when `billing-svc` starts (currently `002_bitcoin_billing.sql`). Initial schema still comes from Docker init (`migrations/001_initial.sql`).

## Useful information (AI)

- Embeds `*.sql` next to `migrate.go` via `//go:embed`.
- Only put idempotent migrations here (IF NOT EXISTS / DROP IF EXISTS).
- Keep canonical copies under `services/billing-svc/migrations/` and copy forward when adding versions.
