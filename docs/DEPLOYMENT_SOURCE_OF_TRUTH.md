# Deployment source of truth

- GitHub repository: `veritasvpn/VeritasVPN`.
- Protected integration branch: `master`.
- Production compute: production node only.
- Workload runtime: K3s; canonical overlay `deploy/k8s/overlays/k3s`.
- Website runtime: Cloudflare Pages from `website/` through `pages-deploy.yml`.
- Public client releases: strict GitHub tag workflow; Linux, Android, and Chrome only until other platforms are signed and supported.
- Production Secrets: Kubernetes Secrets encrypted at rest in K3s; never committed.

The Raspberry Pi and Linux desktop are not application or VPN production nodes. Docker Compose, the Pi recovery path, local website nginx deployment, unsigned macOS DMGs, and automatic `git reset --hard` deployment scripts are retired.

## Change path

1. Create a reviewed change from the current `master`.
2. Require CI, dependency, secret, static, manifest, container, and supported-client checks.
3. Back up and run the restore rehearsal on the Dell.
4. Pull the exact reviewed commit on the production host and confirm a clean worktree.
5. Build immutable local images, record their digests in the K3s overlay, and run `deploy/k8s/scripts/apply.sh k3s`.
6. Require `verify-core.sh`, public health checks, and the external WireGuard E2E workflow.
7. Record the deployed commit and retain the transactional rollback snapshot.

Direct edits to live Kubernetes objects, server source files, Cloudflare Pages files, or client artifacts create drift and must be reconciled back into `master` immediately.

## Release smoke

After every production or client release, follow `docs/RELEASE_SMOKE.md` and the
status board in `docs/SOFT_LAUNCH_OPS_CLOSEOUT.md`:

- CI `prod-smoke.yml` — sign-in, Premium, EdDSA JWT, download SHA, ACAO check
- Hourly `vpn-e2e.yml` — short tunnel
- production host `veritas-tunnel-hold.timer` — 5+ minute hold
- production host `billing-webhook-smoke.sh` — signed BTCPay settle (no mock in prod)
- production host `veritas-jwt-secret-cleanup.timer` — removes `JWT_SECRET` after drain
- `deploy/ops/verify-ssh-wan.sh` / `verify-acao.sh` — host/edge regressions
