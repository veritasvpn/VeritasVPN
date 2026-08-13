# Account Dashboard Implementation Plan

## Useful information (humans)

When a user is **signed in** (email visible in the header), they must **not** see the Log in / Sign up modal. Primary CTAs like **Get VeritasVPN** should open an **account dashboard** similar to Proton VPN’s logged-in web app.

**Current bug:** Hero / CTA buttons use `data-auth-open="signup"`, which always opens the auth modal — even if Firebase already has a session. Navbar correctly shows the logged-in state; those CTAs ignore it.

**Target:** Proton-like dashboard after login:

| Area | Behavior |
|------|----------|
| Sidebar | Home, Subscription, Account, Downloads, Security |
| Header | Upgrade + email + avatar / sign out |
| Home | “Your plan” (Free / Premium) + limits + upgrade path |
| Subscription | Bitcoin upgrade / renew / cancel-at-period-end |
| Downloads | macOS + Chrome (primary), other platforms secondary |
| Account | Email, sign out, (later) password reset |

Visual language stays **Veritas** (dark `#05070a`, cyan→blue accents) — structure inspired by Proton, not a purple clone.

## Useful information (AI)

- Auth today: Firebase on `website/js/auth.js`; billing status via `website/js/billing.js` → `billing-svc`
- Account ID = Firebase UID
- Plans: `free` \| `premium` ($5/mo BTC) — see `docs/BITCOIN_PAYMENTS_IMPLEMENTATION_PLAN.md`
- Do not keep marketing CTAs on `data-auth-open` once session exists; route to `/account/` (or `/app/`)

---

## Goals

1. Fix auth-aware CTAs (logged in → dashboard; logged out → modal).
2. Ship a dedicated logged-in **account app shell** (sidebar + pages).
3. Show plan status from billing API; upgrade with existing Bitcoin checkout.
4. Surface Downloads (macOS + Chrome) inside the dashboard.
5. Gate dashboard routes: no session → redirect home + open sign-in.

Non-goals (this phase): full Proton feature parity (Recovery kit, Appearance themes, NetShield), multi-year pricing cards, mobile native settings sync.

---

## Why the modal still appears (root cause)

```text
[data-auth-open="signup"]  →  always openModal('signup')
```

Used by:

- Hero **Get VeritasVPN**
- CTA **Get Started Now**
- Pricing **Get Started Free**
- Nav **Get Started** (only while logged out — OK)

Navbar uses `onAuthStateChanged` to toggle `#navAuthLoggedOut` / `#navAuthLoggedIn`, but hero CTAs never check `auth.currentUser`.

---

## Architecture

```text
Marketing site (index, downloads, install)
        │
        │  logged out → auth modal
        │  logged in  → /account/
        ▼
Account app  (/account/index.html or /account/#/…)
        │
        ├── Firebase session required
        ├── GET /api/v1/billing/status
        ├── POST checkout (existing billing.js)
        └── Links to install/macos + install/chrome
```

**Routing choice (pick one in Phase 1):**

| Option | Pros | Cons |
|--------|------|------|
| **A. Static multi-page** `/account/`, `/account/subscription.html`, … | Simple, matches current static site | More HTML duplication |
| **B. SPA hash router** `/account/#/home`, `#/subscription` | One shell, Proton-like | Slightly more JS |

**Recommendation:** **B (hash SPA)** under `website/account/` — one shell, sidebar swaps main panels. Fits Proton UX without a React build yet. Migrate to React/Vite later if needed.

---

## Phase 0 — Quick fix (ship first)

| Task | Detail |
|------|--------|
| 0.1 | Add `data-auth-gate="dashboard"` (or similar) for CTAs that mean “enter product” |
| 0.2 | In `auth.js`: if `auth.currentUser` → `location.href = '/account/'`; else open modal |
| 0.3 | Change hero **Get VeritasVPN**, footer CTA, Free plan button to use the gate |
| 0.4 | Keep explicit Log in / Sign up on `data-auth-open` only when logged out |

**Acceptance:** Logged-in user clicks Get VeritasVPN → lands on `/account/` with no modal. Logged-out user still gets the modal.

---

## Phase 1 — Account shell (Proton-like layout)

### 1.1 Information architecture

```text
/account/
  #/                 Home (Your plan)
  #/subscription     Upgrade / manage Premium
  #/downloads        macOS + Chrome (+ others)
  #/account          Profile / sign out
  #/security         Privacy notes / sessions (minimal v1)
```

### 1.2 Layout

```text
┌─────────────┬──────────────────────────────────────────┐
│ Logo        │  [Upgrade]   email@…   [avatar ▾]        │
│             ├──────────────────────────────────────────┤
│ Home        │                                          │
│ Subscription│           Main panel                     │
│ Downloads   │                                          │
│ Account     │                                          │
│ Security    │                                          │
│             │                                          │
│ Sign out    │                                          │
└─────────────┴──────────────────────────────────────────┘
```

- Sidebar: dark Veritas background (`--bg-primary` / `--bg-secondary`)
- Main: slightly elevated (`--bg-secondary` / cards `--bg-card`) — avoid Proton purple
- Active nav: accent bar + muted highlight (like Proton’s left rail)

### 1.3 Files to add

```text
website/account/
  index.html          # shell
  css/account.css     # dashboard-only styles
  js/account-app.js   # router + panel render
  js/account-api.js   # thin wrapper over billing + auth
  README.md
```

Reuse: `auth.js`, `billing.js`, `firebase-config.js`, brand assets.

### 1.4 Auth gate on shell load

```text
onAuthStateChanged →
  if !user → redirect '/' and optionally ?signin=1
  if user  → render shell, fetch billing status
```

**Acceptance:** Visiting `/account/` logged out redirects; logged in shows shell with email in header.

---

## Phase 2 — Home (“Your plan”)

Mirror Proton’s top block:

| Element | Veritas mapping |
|---------|-----------------|
| Plan name | **Veritas Free** or **Veritas Premium** |
| Limits (Free) | 5 locations · 1 device · 2 GB/mo (marketing copy; enforce later in wg-manager) |
| Limits (Premium) | All locations · 5 devices · Unlimited |
| Period end | Show `current_period_end` when Premium |
| Primary CTA | Free → “Upgrade with Bitcoin”; Premium → “Manage subscription” / “Renew” |

Data: `GET /api/v1/billing/status` (`is_premium`, `tier`, `current_period_end`).

**Acceptance:** Plan card matches billing API; badge in marketing nav stays in sync.

---

## Phase 3 — Subscription panel

| Task | Detail |
|------|--------|
| 3.1 | Show Free vs Premium summary |
| 3.2 | Single paid offer: **Premium $5/mo · Bitcoin** (no 12/24-month cards yet — unlike Proton screenshot) |
| 3.3 | Wire **Upgrade** → existing `startPremiumCheckout()` |
| 3.4 | **Cancel at period end** → `POST /api/v1/billing/cancel` (add client helper) |
| 3.5 | Empty/error states if billing-svc down |

Optional later: annual Bitcoin prepaid invoices (out of scope).

**Acceptance:** Upgrade opens mock/real BTCPay; cancel sets `cancel_at_period_end`.

---

## Phase 4 — Downloads panel (inside dashboard)

Same primary offers as public downloads page:

1. **macOS** → `/install/macos.html` (or embed status + link)
2. **Chrome** → `/install/chrome.html` + ZIP

Secondary: Windows, Linux, iOS, Android, Firefox — “Coming soon”.

**Acceptance:** Logged-in user reaches Chrome install without leaving account context (new tab OK).

---

## Phase 5 — Account + Security (minimal)

**Account**

- Display email, UID (optional, collapsed)
- Sign out
- Link to Firebase password reset (existing `sendPasswordResetEmail`)

**Security**

- Short copy: no-logs, WireGuard, Bitcoin payments
- Link to warrant canary / GitHub
- Placeholder “Sessions” / recovery for later

**Acceptance:** Sign out returns to marketing home and clears dashboard access.

---

## Phase 6 — Marketing site integration polish

| Task | Detail |
|------|--------|
| 6.1 | Logo / brand click: logged in → `/account/`; logged out → `/` |
| 6.2 | After successful auth modal → redirect `/account/` (not just close modal) |
| 6.3 | Billing success page → `/account/#/subscription` |
| 6.4 | Hide or rewrite marketing “Get Started” when logged in (“Open dashboard”) |
| 6.5 | `?signin=1` / `?signup=1` query opens modal once for deep links |

---

## Phase 7 — Visual QA vs Proton reference

Use the Proton screenshot as **IA reference only**:

- [ ] Sidebar + main split
- [ ] “Your plan” summary card
- [ ] Upgrade section below plan
- [ ] Header identity (email + menu)
- [ ] Downloads in sidebar

Veritas differences (intentional):

- Dark brand throughout (or dark sidebar + dark main — no light-gray Proton chrome)
- One Premium price (Bitcoin), not 1/12/24 month grid
- No “Unlimited suite” cross-sell banner unless product expands

---

## Suggested milestone order

| Milestone | Outcome |
|-----------|---------|
| **M0** | CTA gate fix (½ day) |
| **M1** | `/account/` shell + auth redirect |
| **M2** | Home plan card + billing status |
| **M3** | Subscription upgrade/cancel |
| **M4** | Downloads panel |
| **M5** | Account/security + post-login redirects |

---

## File / API checklist

**New**

- `website/account/index.html`
- `website/account/css/account.css`
- `website/account/js/account-app.js`
- `website/account/js/account-api.js`
- `docs/ACCOUNT_DASHBOARD_IMPLEMENTATION_PLAN.md` (this file)

**Update**

- `website/js/auth.js` — dashboard gate + post-login redirect
- `website/js/billing.js` — `cancelSubscription()` helper
- `website/index.html` (+ other pages) — CTA attributes
- `website/billing/success.html` — redirect into account

**Existing APIs (reuse)**

- `GET /api/v1/billing/status`
- `POST /api/v1/billing/subscribe`
- `POST /api/v1/billing/cancel`

---

## Testing plan

1. Logged out → Get VeritasVPN → modal.
2. Sign up / sign in → lands on `/account/`.
3. Refresh `/account/` → still logged in; plan loads.
4. Open `/account/` in private window → redirect home.
5. Free user → Upgrade → mock BTCPay → success → Premium on Home.
6. Sidebar Downloads → Chrome install page works.
7. Sign out → cannot open `/account/` without auth.

---

## Open decisions (confirm before build)

1. **Hash SPA vs multi-page** — plan recommends hash SPA under `/account/`.
2. **Post-login always go to dashboard?** — recommend yes for modal success; marketing pages stay public.
3. **Enforce Free limits in dashboard only vs wg-manager** — dashboard shows limits now; enforcement is a later entitlements task.

---

## Relation to other docs

- Payments: `docs/BITCOIN_PAYMENTS_IMPLEMENTATION_PLAN.md`
- Chrome / macOS downloads: `clients/browser-extension/`, `website/install/`
- This doc owns **logged-in web UX**; it does not replace the Tauri desktop app plan
