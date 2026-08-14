# billing.js

## Useful information (humans)

Talks to `billing-svc` for subscription status and Bitcoin (BTCPay / mock) Premium checkout. Uses the Firebase ID token from `auth.js` as `Authorization: Bearer …`.

Default API base: `http://localhost:8083`. Override with `window.VERITAS_BILLING_API`.

## Useful information (AI)

- Call `initBillingUI()` from `main.js` after `initAuthUI()`.
- Elements: `[data-billing-checkout]` starts Premium checkout; `#navPlanBadge` shows subscription status.
- Listens for `veritas-auth-changed` custom events from `auth.js`.
- Account ID on the server is the Firebase UID.
