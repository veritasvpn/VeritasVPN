# Pre-production plan: JWT cutover, Android Always-on tip, release smoke

**Date:** 2026-08-31  
**Status:** Implemented. Live Dell: auth/billing/proxy/wg-manager run **without** `JWT_SECRET` mounts (EdDSA-only). `JWT_SECRET` key retained in `veritas-secrets` for emergency rollback ≥24h. Android tip in tree at 0.2.19 (needs client release). Smoke scripts + `prod-smoke.yml` added; install Dell cron for tunnel-hold when ready.  
**Goal:** Close three remaining soft-launch → public-production gaps without blocking invite-only Linux/Android use.

**Current live state (Dell, 2026-08-31):**
- Auth already has `JWT_ED25519_PRIVATE_KEY` + `JWT_ACTIVE_KEY_ID=veritas-20260829T122029Z` → **new access tokens are EdDSA**.
- Billing, proxy, and wg-manager still mount `JWT_SECRET` for dual-verify of any leftover HS256 tokens.
- Access TTL = `1h`. Refresh tokens are opaque (independent of JWT alg).

---

## 1. Finish JWT migration (HS256 → Ed25519)

### Status
| Phase | Code | Live Dell |
|-------|------|-----------|
| Dual-verify EdDSA + HS256 | Done (`lib/jwt`) | Done |
| Mint Ed25519 only | Done when private key set | **Done** (private key present) |
| Drop HS256 / remove `JWT_SECRET` | **Not done** | **Not done** |
| Tests / compose / secret generator | Partial (HS256-oriented) | N/A |

### Objectives
1. Prove no HS256 access tokens are still accepted in normal traffic.
2. Remove `JWT_SECRET` from auth, billing, proxy (and any other mounts).
3. Tighten verify paths to EdDSA-only; update tests and local compose.
4. Document rollback and key rotation.

### Work items

#### 1A. Observability (half day)
- Add a short-lived metric or structured log on verify: `alg=EdDSA|HS256` (billing + auth + proxy).
- Or one-off Dell probe: mint via sign-in / refresh, decode JWT header `alg`/`kid` (no secret needed for header).
- Confirm sample of client traffic after forced refresh is EdDSA for ≥2× `ACCESS_TOKEN_TTL` (2h+).

#### 1B. Drain window (ops, ≥2h after 1A green)
- Ask soft-launch users to open apps once (forces refresh) or wait 2 hours idle.
- Optional: bump nothing — 1h TTL drains naturally.

#### 1C. Remove `JWT_SECRET` from workloads (half day)
1. Patch live Secret usage / Deployment env:
   - Remove `JWT_SECRET` from `billing-svc`, `veritas-proxy`, `auth-svc`, `wg-manager` if present.
2. Update manifests:
   - `deploy/k8s/base/billing-svc.yaml`
   - `deploy/k8s/base/veritas-proxy.yaml`
   - `deploy/k8s/base/auth-svc.yaml` (keep private + public + active kid only)
3. Rollout restart; smoke: sign-in, `/billing/status`, peer create, Chrome proxy auth if used.
4. After success, remove `JWT_SECRET` key from `veritas-secrets` (or rotate to empty and delete).

**Rollback:** restore `JWT_SECRET` on verifiers **before** any auth mint rollback. Do not delete the secret from vault until Phase 1C is stable ≥24h.

#### 1D. Code harden (half–1 day)
- `lib/jwt`: fail startup if HMAC-only mint is attempted in production; optionally reject HS256 in `ValidateAccessToken` when `JWT_SECRET` unset (already natural).
- `services/browser-proxy`: EdDSA path required; HS256 only if secret present (already); add EdDSA unit test.
- Billing `tokenauth` tests: add `NewVerifierWithKeys` EdDSA case.
- `lib/jwt` tests: dual-verify, kid mismatch, HS256 rejected when secret empty.
- Update `docker-compose*.yml`, `.env.example`, `generate-secrets.sh` to prefer Ed25519; stop requiring `JWT_SECRET`.
- Update `deploy/k8s/SECRETS.md` “remove after migration” → “removed”.

#### 1E. Acceptance
- [ ] Fresh sign-in JWT header `alg=EdDSA`, `kid=veritas-20260829T122029Z` (or current active kid).
- [ ] No pod mounts `JWT_SECRET`.
- [ ] Billing status + WG peer APIs work with EdDSA-only tokens.
- [ ] CI `go test ./lib/jwt/...` + billing + browser-proxy green.
- [ ] Local compose can mint/verify without `JWT_SECRET`.

---

## 2. Android Always-on guidance (in-app tip)

### Status
- Mandatory Always-on gate removed in v0.2.15 (by design).
- `VpnKillSwitch.isLockdownEnabled()` still exists but is unused.
- Marketing/FAQ already describe system Always-on honestly.

### Objective
After first successful connect, show a **dismissible, one-time tip** if lockdown is not already enabled. Never block Connect.

### Work items

#### 2A. Pref + detection (small)
- Store `always_on_vpn_tip_shown` in `SecurePrefs` (`veritasvpn_permissions` or `veritas_vpn_settings`).
- On connect success, if `!VpnKillSwitch.isLockdownEnabled(context)` and tip not shown → set UI flag.

#### 2B. UI (small)
- Soft dialog or banner on `DashboardScreen` when connected (near “CONNECTION SECURED”).
- Copy (suggested):  
  **Stay protected if the tunnel drops**  
  In Android VPN settings, enable Always-on VPN and “Block connections without VPN” for VeritasVPN. The app can’t turn these on for you.
- Actions: **Open VPN settings** → `Settings.ACTION_VPN_SETTINGS`; **Got it** → set pref, dismiss.
- Auto-dismiss on resume if lockdown becomes true.

#### 2C. Wiring
- `MainActivity`: own show flag, deep-link, pref (same pattern as notification permission).
- `DashboardScreen`: render-only props (`showAlwaysOnTip`, `onOpenVpnSettings`, `onDismissAlwaysOnTip`).
- Do **not** reintroduce pre-connect gates.

#### 2D. Ship
- Bump Android `versionCode` / `versionName` with next client release (e.g. 0.2.19).
- Manual test: first connect shows tip; dismiss never returns; with lockdown on, tip skipped.

#### 2E. Acceptance
- [ ] Tip never blocks Connect.
- [ ] Shown at most once per install (unless pref cleared).
- [ ] CTA opens system VPN settings on stock Android.
- [ ] Skipped when Always-on + lockdown already enabled for this app.

---

## 3. Release / ops smoke checklist (automate)

### Status
| Check | Today |
|-------|--------|
| Sign-in + short WireGuard connect | Hourly `vpn-e2e.yml` + `external-wireguard-e2e.sh` |
| Premium / billing status | **Gap** |
| 5+ min tunnel, no 2m flap | **Gap** |
| BTCPay webhook → premium | Manual / docs only |
| Downloads SHA vs GitHub | Release generates SUMS; Pages checks repo files; **no live URL digest check** |

### Objective
One automated gate per release + light ongoing cadence covering the six checklist items.

### Work items

#### 3A. Fast CI smoke (new workflow) — daily + `workflow_dispatch` + after release tag
Script: `deploy/verify/prod-smoke-api.sh` (or similar)

1. Sign-in with `VERITAS_E2E_ACCOUNT_ID` (prefer Premium synthetic).
2. `GET /api/v1/billing/status` → `jq -e '.is_premium == true'`.
3. Decode access JWT header → assert `alg == "EdDSA"` (after JWT cutover; until then allow EdDSA|HS256).
4. Download GitHub release assets for current tag (from Functions source or install page) + `sha256sum -c`.
5. Optional: download via `https://veritasvpn.cloud/downloads/...` and compare digests (cache-bust query).

Keep existing hourly short tunnel E2E unchanged.

#### 3B. Dell daily long-hold (new script + cron)
Script: `deploy/verify/tunnel-hold-e2e.sh`

1. Reuse peer provision + `wg-quick up` from external E2E.
2. Loop 6×60s: handshake age ≤ 120s, egress HTTP OK.
3. Fail on flap / interface down; then revoke peer.

Cron: e.g. `15 4 * * *` on Dell; alert via existing telegram/health path on non-zero exit.

#### 3C. Webhook settle smoke (Dell, careful)
Script: `deploy/verify/billing-webhook-smoke.sh`

- **Preferred:** create invoice via subscribe API for synthetic account → POST **HMAC-signed** `InvoiceSettled` (fixture or BTCPay Greenfield) → assert premium.
- **Do not** enable `ALLOW_MOCK_BTCPAY` in production.
- Real mainnet sat payment remains a manual release checkbox.

#### 3D. Docs checklist (human, per release)
Add short section to `docs/DEPLOYMENT_SOURCE_OF_TRUTH.md` or a `docs/RELEASE_SMOKE.md`:

- [ ] CI release green + GitHub assets present  
- [ ] Prod smoke API (3A) green  
- [ ] Install pages SHA match release  
- [ ] Pages deploy green (Functions point at new tag)  
- [ ] Manual: connect on Android + Linux ≥5 min  
- [ ] Manual or 3C: Bitcoin settle → Premium  

#### 3E. Acceptance
- [ ] One command/CI job covers sign-in + premium + SHA.
- [ ] Daily Dell job covers 5+ min no-flap.
- [ ] Webhook path documented; automated where safe without mock billing in prod.
- [ ] Release PR/template links the checklist.

---

## Suggested order

| Order | Track | Effort | Depends on |
|------|--------|--------|------------|
| 1 | JWT 1A–1C (observe → drain → drop secret) | 0.5–1 day ops | Soft-launch quiet window |
| 2 | JWT 1D tests/compose | 0.5–1 day | After 1C stable |
| 3 | Android Always-on tip + release | 0.5 day + release | Independent of JWT |
| 4 | Smoke 3A CI | 0.5 day | Independent; assert EdDSA after JWT 1C |
| 5 | Smoke 3B Dell hold | 0.5 day | Independent |
| 6 | Smoke 3C webhook | 0.5–1 day | Billing secrets on Dell |

**Parallelism:** Android tip (2) and CI smoke SHA/premium (3A) can start immediately while JWT drain runs.

---

## Out of scope (this plan)

- Chrome / Windows / macOS product launch  
- Multi-node HA  
- Changing Android Always-on to mandatory again  
- Real mainnet payment as sole automated gate  

---

## Done when

1. Cluster verifies **EdDSA-only**; `JWT_SECRET` gone from workloads and secret store.  
2. Android shows a one-time Always-on tip after connect without blocking.  
3. Every release runs automated sign-in + premium + SHA checks; Dell daily proves 5+ min tunnel stability; webhook settle is scripted or explicitly manual on the release checklist.
