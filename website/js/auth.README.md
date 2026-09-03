# auth.js

## Useful information (humans)

Firebase Authentication for the marketing site and shared helpers for the account dashboard.

- Email / password and Google sign-in
- `data-auth-open` — always open the auth modal
- `data-auth-gate="dashboard"` — if logged in, go to `/account/`; if not, open modal then redirect after success
- After successful login from the marketing site, users are sent to the dashboard

## Useful information (AI)

- `initAuthUI({ redirectAfterAuth })` — set `redirectAfterAuth: false` only if a page must not leave after auth
- `goToDashboard(hash)`, `ACCOUNT_PATH`, `requireAuthOrOpenModal`
- Re-exports: `signOut`, `sendPasswordResetEmail`, `onAuthStateChanged`
- Account app: `/account/` (see `website/account/README.md`)

<!-- cookie auth: production Pages deploy uses --commit-dirty=true -->
