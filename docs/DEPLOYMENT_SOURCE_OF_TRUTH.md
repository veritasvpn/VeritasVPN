# Deployment source of truth

- GitHub repository: `veritasvpn/VeritasVPN`.
- Protected integration branch: `master`.
- Production compute: Dell OptiPlex only.
- Workload runtime: K3s; canonical overlay `deploy/k8s/overlays/k3s`.
- Website runtime: Cloudflare Pages from `website/` through `pages-deploy.yml`.
- Public client releases: strict GitHub tag workflow; Linux, Android, and Chrome only until other platforms are signed and supported.
- Production Secrets: Kubernetes Secrets encrypted at rest in K3s; never committed.

The Raspberry Pi and Linux desktop are not application or VPN production nodes. Docker Compose, the Pi recovery path, local website nginx deployment, unsigned macOS DMGs, and automatic `git reset --hard` deployment scripts are retired.

## Change path

1. Create a reviewed change from the current `master`.
2. Require CI, dependency, secret, static, manifest, container, and supported-client checks.
3. Back up and run the restore rehearsal on the Dell.
4. Pull the exact reviewed commit on the Dell and confirm a clean worktree.
5. Build immutable local images, record their digests in the K3s overlay, and run `deploy/k8s/scripts/apply.sh k3s`.
6. Require `verify-core.sh`, public health checks, and the external WireGuard E2E workflow.
7. Record the deployed commit and retain the transactional rollback snapshot.

Direct edits to live Kubernetes objects, server source files, Cloudflare Pages files, or client artifacts create drift and must be reconciled back into `master` immediately.
