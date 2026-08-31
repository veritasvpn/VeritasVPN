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
| SSH WAN lockdown | `veritas-firewall` + off-LAN `.github/workflows/verify-wan-ssh.yml` | **VERIFY** — firewall active; GHA off-LAN probe |
| Pages ACAO `*` | `_headers` + `verify-acao.sh` in prod-smoke | **DONE** — PASS 2026-08-31 |

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
| Tunnel-hold | Workflow on master; first concurrent run failed (401 race) — fixed via shared `veritas-e2e-account` concurrency |
| JWT_SECRET removed | Timer armed on Dell (jpg user); due ≥2026-09-01T18:42:00Z |
| WAN :22 | Firewall active; use GHA `verify-wan-ssh.yml` (LAN probes are false positives) |
| ACAO | `verify-acao.sh` PASS 2026-08-31 (also in prod-smoke) |
| Manual 5+ min connect | _(human)_ |
| BTC settle | _(human or webhook smoke)_ |

---

## Why this does not reopen

- Client versions and Function tags ship together with every release.
- Tunnel-hold is a **systemd timer** with secrets in `/etc/veritasvpn/e2e.env`, not a one-off crontab line.
- JWT cleanup is **time-gated automation**, not a sticky doc reminder.
- Firewall + ACAO have **verify scripts** called from RELEASE_SMOKE / closeout install.
- This status board is the single place to mark closed; re-audits read it first.
