# Website JS entry points

## Live (production)

| File | Role |
|------|------|
| `app-entry-12.js` | Landing page bootstrap (nav, FAQ, auth/billing wiring) |
| `auth-release-12.js` | Auth UI / Firebase session helpers used by the live site |
| `../account/js/account-app-v4.js` | Account dashboard SPA |

Prefer these when editing or reviewing site behavior.

## Legacy

Older `*-v2` (and earlier numbered) bundles under `js/` and `js-v2/` are **legacy**. Keep them only for historical or staging pages; do not wire new pages to them.
