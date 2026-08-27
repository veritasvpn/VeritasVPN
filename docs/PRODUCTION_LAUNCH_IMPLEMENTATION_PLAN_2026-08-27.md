# Production launch implementation plan (2026-08-27)

Goal: fully clear the production-readiness audit so VeritasVPN can be marketed publicly without the critical/high findings recurring.

Source of truth for findings: Cursor canvas `production-readiness-audit` + live Dell verification (2026-08-27).

**Launch gate:** all **Critical** and **High** items Done + verification checklist green. Medium/Low may ship as follow-ups only if explicitly deferred in writing.

**Progress:** Phases 1–3 done (2026-08-27). Phase 3: downloads-v2 → downloads redirect; Windows/macOS coming-soon only; kill-switch copy qualified; Chrome zip 0.3.4+Turnstile; Android APK 0.1.5 SHA `ff7f9cf3…`; Linux v0.2.2 AppImage confirmed to bundle wstunnel. Desktop 0.2.3 AppImage/deb still to publish for mainnet checkout UI.

---

## Principles

1. **Fix paid path first** — broken mainnet checkout / entitlement sync loses paying users immediately.
2. **Fail closed on auth** — Turnstile and JWT secrets must never silently disable in production.
3. **One production overlay** — Dell must not have a divergent `prod` vs `k3s` story.
4. **No secret wipe** — never `kubectl apply` incomplete `secrets.yaml`.
5. **Ship artifacts that match claims** — Linux stealth build, Android kill-switch APK, no fake Windows/macOS links.
6. **Verify on Dell + public edge** after each phase before starting the next.

---

## Phase 0 — Freeze & baseline (30–60 min)

| Step | Action | Done when |
|------|--------|-----------|
| 0.1 | Tag current master + note image digests from live cluster | `git tag` + digest list saved |
| 0.2 | Confirm Dell uses `overlays/k3s` only (not `overlays/prod`) | Docs/scripts point at k3s |
| 0.3 | Snapshot: health PASS, 0 firing alerts, BTCPay IBD=0 | Re-run `veritas-k8s-health` as root |

Do not cut over any overlay until Phase 4.

---

## Phase 1 — Paid path (Critical C3, C4, C5) — Day 1

### 1A. Desktop mainnet checkout allowlist (C3)

| File | Change |
|------|--------|
| `clients/desktop/src/App.tsx` | Accept `https://btcpay-mainnet.veritasvpn.cloud/` (drop or keep testnet only behind `import.meta.env.DEV`) |
| `clients/desktop/package.json` | Bump to `0.2.3` to match tauri if rebuilding |
| `clients/desktop/src-tauri/tauri.conf.json` | Version bump for release |

**Verify:** create subscribe → `checkout_url` host is mainnet → desktop accepts and opens checkout UI.

### 1B. Android mainnet WebView allowlist (C4)

| File | Change |
|------|--------|
| `android/.../PaymentCheckoutScreen.kt` | Allow `btcpay-mainnet.veritasvpn.cloud`; reject unknown hosts; keep `veritasvpn.cloud` success return + `bitcoin:` |

**Verify:** load a mainnet checkout URL in WebView; page renders; return to site closes + refreshes plan.

**Ship:** rebuild signed APK, publish `website/downloads/veritasvpn-android.apk` + Pages function if used; bump `versionCode`/`versionName`.

### 1C. Billing entitlement sync (C5)

| File | Change |
|------|--------|
| `services/billing-svc/internal/service/service.go` | `subscription.renewed` must include `account_id`, `period_end` (and ideally `period_start`, `tier`) |
| Auth/wg consumers | Confirm they already consume these fields; add unit tests for empty vs present |
| Optional but recommended (H5) | Activate premium **only** on `InvoiceSettled` (remove `InvoiceReceivedPayment` from settle switch, or gate behind confirmations) |

**Verify:**

1. Unit test: renewed event JSON contains `account_id`.
2. On Dell: pay/test webhook (or replay settled invoice) → auth `accounts.subscription_tier=premium` and wg-manager tier cache updates without waiting for expire path.
3. Rebuild/push/pin `billing-svc` (+ auth/wg if consumer code changes).

**Phase 1 exit:** desktop + Android can open mainnet checkout; a settled invoice flips entitlement end-to-end.

---

## Phase 2 — Auth hard gate (Critical C1, C2 + High H1, H6, H7) — Day 1–2

### 2A. Turnstile always-on when secret set (C1)

| Area | Change |
|------|--------|
| `auth-svc/.../handler/http.go` | If `TurnstileEnabled()`, **always** require token on `register` + `register-anonymous` (remove Origin / `X-Veritas-Client` opt-in). Keep header as telemetry only. |
| Production | `TURNSTILE_SECRET_KEY` **required** (not optional) on auth Deployment; process **fatals** if `ENVIRONMENT=production` and secret empty |
| `clients/desktop/src/auth.ts` | Send `X-Veritas-Client: desktop` + `turnstile_token` (WebView/managed widget or shared mobile page pattern) |
| `clients/browser-extension/js/auth.js` | Same: header + token (or route signup through website only) |
| `website/js/auth.js` (+ release bundle) | Send `X-Veritas-Client: web` |
| Install pages (H2) | Point `install/{linux,android,chrome}.html` at Turnstile-enabled auth bundle (`auth-release-12` or successor); add `#authTurnstile` |

**Verify:**

- No Origin, empty body → `register-anonymous` **400** verification failed.
- Web Origin + empty token → 400.
- Android with valid token → 201.
- Desktop/extension with token → 201; without → 400.

### 2B. Account ID is not a password (C2)

| Change | Detail |
|--------|--------|
| Server | `SignInWithAccountID` only succeeds if account is **anonymous** (no email / account_type anonymous). Email accounts must use password (or future recovery code). |
| Clients | Update copy: “anonymous account ID only”; remove “any account ID”. |
| Optional stronger | Bind anonymous restore to a high-entropy recovery secret stored hashed (follow-up if timeline allows). |

**Verify:** email account_id → signin-account fails; anonymous ID → succeeds; rate limits unchanged.

### 2C. JWT / token hygiene (H1, H6, H7)

| Item | Change |
|------|--------|
| H1 JTI | `lib/jwt/jwt.go` `generateJTI` use `crypto/rand` |
| H7 reset tokens | Hash at rest like email verification; compare hashes |
| H6 download-account | Prefer `Authorization: Bearer`; deprecate `?token=` (accept briefly with warning log, then remove) |
| Bonus | `envRequired` actually requires non-empty; JWT_SECRET min length in production |

**Verify:** unit tests for JTI uniqueness; reset flow still works; download with Bearer works; `?token=` rejected or deprecated per plan.

**Phase 2 exit:** captcha mandatory on all register paths; account_id cannot hijack email accounts; JWT/reset/download hardened; auth-svc image rebuilt + digest pinned on Dell.

---

## Phase 3 — Marketing / download honesty (High H2, H3 + Medium M4) — Day 2

| Item | Action |
|------|--------|
| H3 `downloads-v2.html` | Rewrite to match FEATURES_SHIPPED **or** delete and redirect to `downloads.html` / index |
| Fake release links | Remove Windows.exe / macOS.dmg until real artifacts exist |
| H2 Install auth | Completed in 2A |
| M4 Kill switch copy | Qualify homepage/premium bullets: Linux/Android mandatory; Chrome browser-only |
| Version drift | Align desktop package.json ↔ tauri 0.2.x; Chrome `?v=` with zip version |
| Linux artifacts | Confirm GitHub release `v0.2.2` (or new `v0.2.3`) AppImage/deb contain `wstunnel`; Functions point at that tag |
| Android APK | Publish post–kill-switch + Turnstile + mainnet checkout build; refresh SHA on install page |

**Verify:** crawl live site for `btcpay.veritasvpn.cloud`, “Windows early access”, 404 download links; Linux/Android download HEAD 200; stealth binary present in AppImage.

**Phase 3 exit:** public pages cannot contradict shipped platforms or payment hosts.

---

## Phase 4 — Deploy / GitOps safety (High H8–H11) — Day 2–3

### 4A. Single overlay (H8)

1. Make `overlays/k3s` the only production path **or** make `overlays/prod` identical (digests, stealth on, same CORS, same securityContexts).
2. Update `deploy/verify/production-readiness.sh`, cutover docs, `apply.sh` defaults to **not** apply base alone / not apply stale prod.
3. Pin `wstunnel` digest in the canonical overlay; base may keep `:latest` only as a name for kustomize rewrite.

### 4B. Secrets apply safety (H9)

1. Remove `secrets.yaml` from `btcpay-mainnet` (and testnet if kept) kustomization — mirror veritas base comment.
2. Document: `kubectl create secret` / sealed-secrets / one-shot script only.
3. Add CI check: `kustomize build` must not require missing secrets files for default overlays.

### 4C. Toolchain (H10)

1. Bump all service Dockerfiles `golang:1.22-alpine` → supported (e.g. `1.24` or `1.25`) + Alpine runtime stay ≥ 3.22.
2. Rebuild auth, billing, wg-manager, veritas-agent, telegram-notifier as needed; push; pin digests; roll Dell.

### 4D. Monitoring completeness (H11)

1. Redis exporter: inject `REDIS_PASSWORD` / use `redis://:pass@host:6379`.
2. NetworkPolicy: allow `monitoring` → `wg-manager:8080` (and any other scraped veritas targets missing allows).
3. Confirm Prometheus targets for redis-exporter + wg-manager are **up**.

### 4E. Related High from deploy audit (bundle with 4)

| Item | Action |
|------|--------|
| Turnstile optional in YAML | Done in 2A |
| BTCPay keys optional on billing | In production overlay, require BTCPAY_* (fail pod if missing) |
| production-readiness.sh testnet | Point at `btcpay-mainnet` only |
| Go CORS / tauri origins | Add `tauri://localhost` / `https://tauri.localhost` to auth CORS if desktop WebView needs it **after** Turnstile always-on |

**Phase 4 exit:** `kubectl apply -k` of canonical overlay is safe; monitoring not blind; digests pinned; no testnet gate in readiness script.

---

## Phase 5 — Host / edge hardening (High H4 + Medium M1, M2, M3) — Day 3

| Item | Action | Owner |
|------|--------|-------|
| H4 SSH WAN | nft/ufw: deny `:22` from public WAN; allow LAN `192.168.0.0/16` and/or Tailscale only | Human on Dell (sudo) |
| M1 ACAO `*` | Cloudflare Pages / Transform Rules: stop emitting `Access-Control-Allow-Origin: *` on site | Human CF dashboard **or** Pages `_headers` equivalent if supported |
| M2 testnet NS | Scale already 0 → delete `btcpay` NS after backup confirmation; archive `deploy/k8s/btcpay/` or mark deprecated | Agent + human confirm |
| M3 rate-limit IP | Prefer `X-Real-IP` from nginx only; ignore client-supplied `CF-Connecting-IP` unless request came from Cloudflare tunnel hop | Agent |

**Verify:** external SSH to `170.51.31.139:22` fails; LAN/Tailscale SSH works; live site HEAD has no `ACAO: *`; health no longer needs testnet.

**Phase 5 exit:** node not casually SSH-scannable; edge headers match security story.

---

## Phase 6 — Remaining Medium / Low (same week or explicit defer)

Ship if time; otherwise list as known follow-ups in FEATURES / STATUS:

| ID | Work |
|----|------|
| Billing webhook race | Transaction / row lock around settle |
| Billing logout blacklist | Share Redis blacklist with billing verifier |
| Password reset enumeration | Uniform 200 responses |
| PII in logs | Hash emails via `logging.HashIdentifier` |
| Linux IPv6 kill switch | Fail closed on IPv6 blackhole (or disable IPv6) |
| Chrome `<all_urls>` zip | Rebuild zip from source host permissions; fix CI |
| Floating init tags | Digest-pin `alpine`/`busybox` inits |
| Health script | Assert `daemonset/veritas-wstunnel` ready |
| PDB vs replicas=1 | Document single-node HA limits or adjust PDB |

---

## Build / deploy sequence (repeatable)

```text
1. Code + tests locally
2. rsync → Dell /home/jpg/VeritasVPN
3. docker build --network=host + push localhost:31500/<svc>:<tag>
4. Update overlays/k3s digests
5. kubectl set image / apply -k (never apply incomplete secrets)
6. Rollout status + health script
7. Desktop: tauri build → gh release → update Functions tag
8. Android: assembleRelease → publish APK + SHA
9. git commit on Dell → git push (JPG19 cannot push)
10. Confirm Pages deploy green (no >25MB files in website/)
```

---

## Final launch verification checklist

Copy and tick before public marketing:

### Edge / site

- [ ] CSP has **no** `btcpay.veritasvpn.cloud`
- [ ] No `Access-Control-Allow-Origin: *` on marketing site (or accepted risk documented)
- [ ] `downloads-v2` gone or accurate; no 404 Windows/macOS
- [ ] Install pages signup works with Turnstile
- [ ] Kill switch copy qualified on homepage bullets

### Auth

- [ ] `curl` anonymous register without Origin → **not** 201
- [ ] Web Origin without token → verification failed
- [ ] Desktop/extension send client header + token (or signup disabled)
- [ ] Email account_id cannot `signin-account`
- [ ] JTIs unique across two tokens
- [ ] Reset tokens not plaintext in DB

### Billing / clients

- [ ] Desktop accepts mainnet checkout URL
- [ ] Android WebView loads mainnet checkout
- [ ] Settled invoice → premium on account + WG path
- [ ] Prefer settle-only on `InvoiceSettled`

### Cluster

- [ ] Canonical overlay digests match running pods
- [ ] wstunnel digest-pinned, no hostPath, Ready
- [ ] Redis exporter + wg-manager Prometheus targets up
- [ ] `veritas-k8s-health` PASS as root
- [ ] 0 unexpected firing alerts
- [ ] BTCPay secrets not in kustomize resources
- [ ] SSH not open on WAN (or exception documented)

### Artifacts

- [ ] Linux release AppImage/deb include `wstunnel`
- [ ] Android APK version/SHA match install page
- [ ] Chrome zip matches source permissions intent

---

## Suggested calendar (aggressive public release)

| Day | Focus |
|-----|--------|
| D0 | Phase 0 baseline |
| D1 | Phase 1 (checkout + entitlement) + start Phase 2 |
| D2 | Finish Phase 2–3; start Phase 4 rebuilds |
| D3 | Phase 4–5 harden; full checklist |
| D4 | Soft launch / friends & family; watch alerts + signup abuse |

---

## Explicitly needs a human (cannot be agent-only)

1. Dell **sudo** for SSH firewall / health install (if needed again).
2. Cloudflare dashboard if Pages ACAO cannot be fixed via repo alone.
3. Confirm deleting `btcpay` testnet namespace / PVCs.
4. Any new Turnstile widget domains if desktop uses a custom origin.
5. Real payment smoke test with small mainnet amount (or BTCPay test invoice if available).

---

## Out of scope for this plan (do not block launch)

- Windows / macOS native clients
- Android Play Store listing
- Multi-node HA / multi-region
- Replacing self-signed wstunnel TLS with public CA (document stealth MITM assumptions instead)
- Hardware upgrades
