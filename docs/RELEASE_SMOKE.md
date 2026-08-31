# Release smoke checklist

Run after every client or backend production release. Prefer automation; keep human
ticks only for UX and real Bitcoin when needed.

**Closeout status:** see `docs/SOFT_LAUNCH_OPS_CLOSEOUT.md` (single board for
ship/ops + host/edge leftovers).

## Automated

| Check | How |
|-------|-----|
| Sign-in + Premium + EdDSA JWT + download SHA | `.github/workflows/prod-smoke.yml` → `deploy/verify/prod-smoke-api.sh` (daily + release + dispatch) |
| ACAO `*` absent on marketing URLs | `deploy/ops/verify-acao.sh` (also invoked from prod-smoke) |
| Short WireGuard connect | `.github/workflows/vpn-e2e.yml` → `deploy/verify/external-wireguard-e2e.sh` (hourly) |
| 5+ min tunnel, no 2m flap | Dell **systemd** `veritas-tunnel-hold.timer` → `deploy/verify/tunnel-hold-e2e.sh` |
| BTCPay webhook → Premium | Dell → `BTCPAY_WEBHOOK_SECRET=… deploy/verify/billing-webhook-smoke.sh` (**never** mock in prod) |
| JWT_SECRET gone from Secret (post-drain) | `veritas-jwt-secret-cleanup.timer` → `delete-jwt-secret-after.sh` |
| SSH WAN closed | `deploy/ops/verify-ssh-wan.sh` from off-LAN |

### Dell install (once)

```bash
sudo bash /home/jpg/VeritasVPN/deploy/ops/install-soft-launch-closeout.sh
# Edit /etc/veritasvpn/e2e.env → VERITAS_E2E_ACCOUNT_ID=<premium synthetic>
sudo systemctl enable --now veritas-tunnel-hold.timer
```

### Webhook smoke notes

- Prefer `VERITAS_WEBHOOK_SMOKE_ACCOUNT_ID`.
- Real mainnet sat payment remains an optional manual release checkbox.

## Per-release human checklist

- [ ] CI release workflow green; GitHub assets + `SHA256SUMS` present
- [ ] `prod-smoke` workflow green (or `deploy/verify/prod-smoke-api.sh`)
- [ ] Install pages / Functions point at the new tag; SHAs match release
- [ ] Pages deploy green
- [ ] `verify-acao.sh` PASS
- [ ] Manual: Android + Linux connect ≥5 minutes
- [ ] Manual **or** `billing-webhook-smoke.sh`: Bitcoin settle → Premium
- [ ] No workload mounts `JWT_SECRET`; after drain window Secret key removed

## Related

- Closeout board: `docs/SOFT_LAUNCH_OPS_CLOSEOUT.md`
- Plan: `docs/PRE_PROD_JWT_ALWAYS_ON_SMOKE_PLAN.md`
- Deploy path: `docs/DEPLOYMENT_SOURCE_OF_TRUTH.md`
