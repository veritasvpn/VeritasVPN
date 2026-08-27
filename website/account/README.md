# account/

## Useful information (humans)

Logged-in VeritasVPN dashboard (Proton-style shell):

- **Home** — current subscription status
- **Subscription** — Bitcoin upgrade / renew / cancel
- **Downloads** — Android, Linux, Chrome
- **Account** — profile, password reset, sign out
- **Security** — privacy notes

URL: `/account/` (hash routes: `#/`, `#/subscription`, …).

## Useful information (AI)

- Entry: `index.html` + `js/account-app.js`
- Auth gate via Firebase `onAuthStateChanged`; unauthenticated → `/?signin=1`
- Billing via `/js/billing.js` (`fetchBillingStatus`, `startPremiumCheckout`, `cancelSubscription`)
- Marketing CTAs use `data-auth-gate="dashboard"` from `/js/auth.js`
- Styles: `css/account.css` + shared `/css/style.css`
