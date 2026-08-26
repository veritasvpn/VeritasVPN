# Bitcoin Payments Implementation Plan

## Useful information (humans)

VeritasVPN will take **Bitcoin only** for paid subscriptions (on-chain first; Lightning optional later). Card/Stripe and Monero are **out of scope** for this phase.

**Current product offer (website):**

| Plan | Price | Intent |
|------|-------|--------|
| Free | $0 | Sign up via Firebase; limited access |
| Premium | $3 / month or $30 / year | Paid via Bitcoin; full access |

This document is the build plan to make Premium purchasable end-to-end. The marketing site already shows Free + Premium; payment checkout is not live yet.

## Useful information (AI)

Existing code to extend (do not reinvent):

- `services/billing-svc/` — HTTP billing service, subscription models, migrations
- `services/billing-svc/internal/provider/btcpay.go` — BTCPay invoice create + webhook stub
- Firebase Auth on `website/` — identity for “who is paying”
- Tiers in data model: prefer `free` | `premium` (map away from older annual/bi-annual pricing in the big `IMPLEMENTATION_PLAN.md` for this phase)

Payment provider decision for v1: **self-hosted BTCPay Server**, Bitcoin (BTC) only.

---

## Goals

1. User can create a Free account (Firebase) with no payment.
2. User can upgrade to Premium ($3 USD/month or $30/year) by paying Bitcoin via BTCPay.
3. On confirmed payment, `billing-svc` marks subscription `premium` with a period end (~30 days).
4. Expired Premium falls back to Free (or revoked premium entitlements) without manual ops.

Non-goals (this phase): Stripe, Monero, automatic Lightning-only UX, multi-year plans, gift cards, affiliate payouts.

---

## Architecture (target)

```
Website (logged-in user)
    │  POST /api/v1/billing/subscribe { tier: premium }
    ▼
billing-svc
    │  create invoice ($5 USD → BTC amount via BTCPay)
    ▼
BTCPay Server  ──checkout link──► User pays BTC
    │
    │  webhook InvoiceSettled / InvoiceReceivedPayment
    ▼
billing-svc
    │  activate/renew Subscription (premium, +30d)
    │  emit event (NATS) for auth/wg entitlement updates
    ▼
Clients / WG Manager honor subscription_tier
```

---

## Phase 0 — Product & site (done / keep in sync)

- [x] Website pricing: Free + Premium ($5/mo) only
- [x] Messaging: Bitcoin as the payment method (no Monero/Lightning claims until live)
- [x] CTAs: Free → Firebase sign-up; Premium → “Upgrade with Bitcoin” (checkout live; mock BTCPay in local dev)

**Acceptance:** Pricing section matches Free / $5 Premium; no third plan card.

---

## Phase 1 — BTCPay Server ops

| Task | Detail | Status |
|------|--------|--------|
| 1.1 | Deploy BTCPay Server (Docker) on a dedicated VPS; HTTPS + reverse proxy | Ops (prod) |
| 1.2 | Connect a Bitcoin wallet (on-chain). Document backup of seed / recovery | Ops (prod) |
| 1.3 | Create a Store; note `storeId`, API key, webhook secret | Ops (prod) |
| 1.4 | Configure webhook URL → `https://<api>/api/v1/billing/webhook/btcpay` | Ops (prod) |
| 1.5 | Enable only BTC payment method on the store (disable others) | Ops (prod) |
| 1.6 | Test invoice creation + settle on BTCPay **testnet** or low-value mainnet | Ops (prod) |

**Local/dev:** leave `BTCPAY_API_KEY` empty → **mock checkout** on billing-svc (no real BTC).

**Env vars (billing-svc):** implemented in `lib/config` + compose / `.env.example`.

---

## Phase 2 — Harden `billing-svc` (Bitcoin path only)

- [x] BTCPay client: store ID, webhook secret, USD amount
- [x] Persist payment records; pending until settled
- [x] Webhook settle → activate/renew premium (+30d)
- [x] Idempotent settle by invoice ID
- [x] `GET /api/v1/billing/status` (Firebase auth)
- [x] Expiry worker (hourly)
- [x] Account ID = Firebase UID
- [x] Stripe subscribe path disabled (Bitcoin-only API)

---

## Phase 3 — Website checkout UX

- [x] Premium requires Firebase login
- [x] Call billing API with ID token; open checkout
- [x] Success/cancel pages under `website/billing/`
- [x] Nav plan badge (Free / Premium)

---

## Phase 4 — Entitlements

- [ ] Wire `wg-manager` / auth to honor billing tier (next)
- [x] Free vs Premium product definitions on website

---

## Phase 5 — Renewals

- [x] Paying again while premium extends from current period end
- [ ] Reminder emails before expiry (optional later)

---

## Phase 6 — Security & ops checklist

- [ ] Webhook signature required in production
- [ ] Rate-limit subscribe endpoint per account
- [ ] Never trust client-reported “I paid”
- [ ] Wallet backups tested
- [ ] Runbook: stuck invoice, under/over-pay, expired invoice
- [ ] Authorized domains / CORS for website → API
- [ ] No analytics of payment graph beyond what’s needed for support

---

## Suggested milestone order

| Milestone | Outcome |
|-----------|---------|
| M1 | Site shows Free + $5; BTCPay deployed on testnet |
| M2 | `billing-svc` create invoice + settle webhook → premium in DB |
| M3 | Website checkout + status in navbar |
| M4 | Entitlements enforced in VPN path |
| M5 | Renew flow + expiry worker |

---

## Pricing constants (source of truth for this phase)

```text
PLAN_FREE     = free     @ $0
PLAN_PREMIUM  = premium  @ $5 USD / 30 days
PAYMENT_RAIL  = bitcoin via BTCPay
```

Update website copy and `PREMIUM_PRICE_USD` together when price changes.

---

## Relation to `IMPLEMENTATION_PLAN.md`

The root plan still mentions Stripe, Monero discounts, and Annual/Bi-annual tiers. **For shipping payments now, this document wins:** Bitcoin-only, Free + $5 Premium. Reconcile the root plan in a later edit so Phase 6 there matches this scope.
