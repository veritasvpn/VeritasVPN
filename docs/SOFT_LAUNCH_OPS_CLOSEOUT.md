# Soft-launch ops closeout (2026-08-31)

**Goal:** Close remaining ship/ops + host/edge gaps so they stay closed under re-audit.

**Done when:** each item has lasting automation or an explicit verified-closed status below (not a sticky “todo”).

---

## Status board (update when verified)

| Item | Lasting mechanism | Status |
|------|-------------------|--------|
| Android/Linux **v0.2.19** release + Functions/SHA | Tag + `release.yml` + install pages + Functions pin | **pending release** |
| Dell tunnel-hold | `deploy/systemd/veritas-tunnel-hold.{service,timer}` + `/etc/veritasvpn/e2e.env` | **pending install** |
| prod-smoke green | `.github/workflows/prod-smoke.yml` + GH secret `VERITAS_E2E_ACCOUNT_ID` | **pending first run** |
| Remove `JWT_SECRET` from Secret | `deploy/k8s/scripts/delete-jwt-secret-after.sh` + timer (after cutover+24h) | **armed; delete after 2026-09-01T18:42:00Z** |
| RELEASE_SMOKE tick | This file + `docs/RELEASE_SMOKE.md` evidence section | **in progress** |
| SSH WAN lockdown | `deploy/node/veritas-firewall` + `deploy/ops/verify-ssh-wan.sh` | **verify on install** (Dell script already matches repo md5) |
| Pages ACAO `*` | `website/_headers` unset + `deploy/ops/verify-acao.sh` (+ optional CF Transform) | **verify live; add Transform if regression** |

Cutover reference: JWT mounts removed **2026-08-31T18:42:00Z** (Dell).

---

## 1. Release v0.2.19

1. Bump desktop to `0.2.19` (Android already `versionCode=20` / `0.2.19`).
2. Point Functions + install kickers at `v0.2.19`.
3. Commit → push → `git tag -a v0.2.19` → push tag → wait for `release.yml`.
4. Refresh `website/downloads/SHA256SUMS` + install page digests + local APK; commit → Pages deploy.
5. Run `prod-smoke` with `release_tag=v0.2.19`.

## 2. Tunnel-hold (Dell)

```bash
sudo bash deploy/ops/install-soft-launch-closeout.sh
# requires /etc/veritasvpn/e2e.env with VERITAS_E2E_ACCOUNT_ID=
sudo systemctl status veritas-tunnel-hold.timer
```

## 3. prod-smoke

```bash
gh workflow run prod-smoke.yml -R veritasvpn/VeritasVPN -f release_tag=v0.2.19
# or: VERITAS_E2E_ACCOUNT_ID=… REQUIRE_EDDSA=true RELEASE_TAG=v0.2.19 deploy/verify/prod-smoke-api.sh
```

Confirm secret exists: `gh secret list -R veritasvpn/VeritasVPN | grep VERITAS_E2E`.

## 4. JWT_SECRET deletion

Armed oneshot/timer deletes key **only after** `/etc/veritasvpn/jwt-cutover-at` + 24h and only if no workload mounts `JWT_SECRET`. Manual:

```bash
sudo bash deploy/k8s/scripts/delete-jwt-secret-after.sh --force-if-due
```

## 5. SSH WAN

```bash
sudo bash deploy/ops/install-soft-launch-closeout.sh   # reinstalls firewall if drifted
# From an off-LAN host:
PUBLIC_IP=… bash deploy/ops/verify-ssh-wan.sh
```

## 6. ACAO

```bash
bash deploy/ops/verify-acao.sh
```

If it fails: Cloudflare → Transform Rule → Response header → Remove `Access-Control-Allow-Origin` for `veritasvpn.cloud` / `www`.

## 7. Evidence (fill after run)

| Check | Evidence |
|-------|----------|
| Release tag | |
| prod-smoke run URL | |
| Tunnel-hold timer | |
| JWT_SECRET removed | |
| WAN :22 | |
| ACAO | |
| Manual 5+ min connect | _(human)_ |
| BTC settle | _(human or webhook smoke)_ |

---

## Why this does not reopen

- Client versions and Function tags ship together with every release.
- Tunnel-hold is a **systemd timer** with secrets in `/etc/veritasvpn/e2e.env`, not a one-off crontab line.
- JWT cleanup is **time-gated automation**, not a sticky doc reminder.
- Firewall + ACAO have **verify scripts** called from RELEASE_SMOKE / closeout install.
- This status board is the single place to mark closed; re-audits read it first.
