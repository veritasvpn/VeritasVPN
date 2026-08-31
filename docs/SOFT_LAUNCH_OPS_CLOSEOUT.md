# Soft-launch ops closeout (2026-08-31)

**Goal:** Close remaining ship/ops + host/edge gaps so they stay closed under re-audit.

**Done when:** each item has lasting automation or an explicit verified-closed status below (not a sticky “todo”).

---

## Status board (update when verified)

| Item | Lasting mechanism | Status |
|------|-------------------|--------|
| Android/Linux **v0.2.19** release + Functions/SHA | Tag + `release.yml` + install pages + Functions pin | **DONE** — [release](https://github.com/veritasvpn/VeritasVPN/releases/tag/v0.2.19) |
| Tunnel-hold (5+ min) | `.github/workflows/tunnel-hold.yml` daily (+ optional Dell systemd) | **DONE** — workflow on master; concurrency shared with vpn-e2e |
| prod-smoke green | `.github/workflows/prod-smoke.yml` | **DONE** — [run](https://github.com/veritasvpn/VeritasVPN/actions/runs/33444007922) |
| Remove `JWT_SECRET` from Secret | user timer `veritas-jwt-secret-cleanup` + script | **ARMED** — due ≥2026-09-01T18:42:00Z (auto) |
| RELEASE_SMOKE tick | This board + `docs/RELEASE_SMOKE.md` | **DONE** for automated items; manual UX/BTC still human |
| SSH WAN lockdown | `veritas-firewall` + off-LAN `.github/workflows/verify-wan-ssh.yml` | **DONE** — [off-LAN PASS](https://github.com/veritasvpn/VeritasVPN/actions/runs/33444195855) |
| Pages ACAO `*` | `_headers` + `verify-acao.sh` in prod-smoke | **DONE** — PASS 2026-08-31 |
| Agent heartbeat + public `:443` | `deploy/ops/verify-agent-health.sh` + pinned agent digest | **DONE** — agent `@sha256:62905883…` (2026-08-31); online `wg_port=443` |

Cutover reference: JWT mounts removed **2026-08-31T18:42:00Z** (Dell).

---

## 1. Release v0.2.19

1. Bump desktop to `0.2.19` (Android already `versionCode=20` / `0.2.19`).
2. Point Functions + install kickers at `v0.2.19`.
3. Commit → push → `git tag -a v0.2.19` → push tag → wait for `release.yml`.
4. Refresh `website/downloads/SHA256SUMS` + install page digests + local APK; commit → Pages deploy.
5. Run `prod-smoke` with `release_tag=v0.2.19`.

## 2. Tunnel-hold

**Preferred (no Dell sudo):** GitHub Actions `.github/workflows/tunnel-hold.yml` (daily).

**Optional on Dell** (when you have sudo once):

```bash
sudo bash deploy/ops/install-soft-launch-closeout.sh
# Edit /etc/veritasvpn/e2e.env → VERITAS_E2E_ACCOUNT_ID=
sudo systemctl enable --now veritas-tunnel-hold.timer
```

## 3. prod-smoke

```bash
gh workflow run prod-smoke.yml -R veritasvpn/VeritasVPN -f release_tag=v0.2.19
# or: VERITAS_E2E_ACCOUNT_ID=… REQUIRE_EDDSA=true RELEASE_TAG=v0.2.19 deploy/verify/prod-smoke-api.sh
```

Confirm secret exists: `gh secret list -R veritasvpn/VeritasVPN | grep VERITAS_E2E`.

## 4. JWT_SECRET deletion

**No root required** (jpg user timer):

```bash
bash deploy/ops/install-jwt-cleanup-user.sh
```

System-wide alternative: `sudo bash deploy/ops/install-soft-launch-closeout.sh`.

Manual when due:

```bash
bash deploy/k8s/scripts/delete-jwt-secret-after.sh --force-if-due
```

## 5. SSH WAN

Dell intentionally allows SSH from **LAN + Tailscale** only. Probing from the LAN
or from the node itself is **not** a WAN test.

```bash
# Off-LAN (GitHub Actions — lasting):
gh workflow run verify-wan-ssh.yml -R veritasvpn/VeritasVPN

# Optional host reinstall when you have sudo:
sudo bash deploy/ops/install-soft-launch-closeout.sh
```

If the off-LAN probe fails (port open), reinstall `veritas-firewall` and confirm
router does not DNAT WAN:22 to the host.

## 6. ACAO

```bash
bash deploy/ops/verify-acao.sh
```

If it fails: Cloudflare → Transform Rule → Response header → Remove `Access-Control-Allow-Origin` for `veritasvpn.cloud` / `www`.

## 7. Evidence (fill after run)

| Check | Evidence |
|-------|----------|
| Release tag | `v0.2.19` — https://github.com/veritasvpn/VeritasVPN/releases/tag/v0.2.19 |
| prod-smoke run URL | https://github.com/veritasvpn/VeritasVPN/actions/runs/33444007922 (success) |
| Tunnel-hold | Workflow on master; shared `veritas-e2e-account` concurrency; re-run after agent pin |
| JWT_SECRET removed | Timer armed on Dell (jpg user); due ≥2026-09-01T18:42:00Z |
| WAN :22 | https://github.com/veritasvpn/VeritasVPN/actions/runs/33444195855 (PASS; LAN probes are false positives) |
| ACAO | `verify-acao.sh` PASS 2026-08-31 (also in prod-smoke) |
| Agent health | `verify-agent-health.sh` PASS after pin `sha256:62905883a2156ea86d530fe26d28d4f1d0a7b2cac31107bbe00fc7beb038d3fc` |
| Manual 5+ min connect | _(human)_ |
| BTC settle | _(human or webhook smoke)_ |

---

## Agent pin / WAN handshake (do not regress)

**Symptom class:** GHA `vpn-e2e` / `tunnel-hold` get peers but no handshake; agent logs
`heartbeat returned 401` while peer stream still works.

**Cause:** stale `veritas-agent` image (no Bearer on heartbeat; registers `wg_port=51820`
instead of public `WG_PUBLIC_PORT=443`). WAN only DNATs UDP **443→51820**.

**Lasting controls:**

1. Always pin agent digest in `deploy/k8s/overlays/k3s/kustomization.yaml` after rebuild.
2. After any wg-manager or agent image change: `bash deploy/ops/verify-agent-health.sh`.
3. Never leave `digest:` empty in kustomize image pins (breaks apply silently).

```bash
# Dell rebuild + pin (example)
TAG=agent-$(date -u +%Y%m%d%H%M%S)
docker build --network=host -t localhost:31500/veritas-agent:$TAG -f services/veritas-agent/Dockerfile .
docker push localhost:31500/veritas-agent:$TAG
# set digest in overlay, then:
kubectl apply -k deploy/k8s/overlays/k3s
kubectl -n veritas delete pod -l app=veritas-agent
bash deploy/ops/verify-agent-health.sh
```

---

## Why this does not reopen

- Client versions and Function tags ship together with every release.
- Tunnel-hold is a **GHA workflow** (and optional systemd timer), not a one-off crontab line.
- JWT cleanup is **time-gated automation**, not a sticky doc reminder.
- Firewall + ACAO + **agent health** have verify scripts; ACAO runs inside prod-smoke.
- This status board is the single place to mark closed; re-audits read it first.
