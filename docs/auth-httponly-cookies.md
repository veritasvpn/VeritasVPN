# HttpOnly refresh cookies (website)

## Goal

Stop persisting the long-lived refresh token in JavaScript-accessible storage on the website, so XSS cannot steal it. Native apps keep Bearer + JSON refresh.

## Design

| Client | Access token | Refresh token |
|--------|--------------|---------------|
| Website (`X-Veritas-Client: web`) | Memory + `Authorization: Bearer` | HttpOnly cookie `veritas_rt` |
| Android / desktop / extension | App storage + Bearer | JSON body (unchanged) |

### Cookie

- Name: `veritas_rt`
- Flags: `HttpOnly; Secure; SameSite=Lax; Domain=.veritasvpn.cloud; Path=/api/v1/auth`
- Max-Age: `REFRESH_TOKEN_TTL` (7d)

### CSRF

Same-site credentialed fetch from `veritasvpn.cloud` → `api.veritasvpn.cloud`. Cookie-based refresh/logout require `X-Veritas-Client: web` (non-simple header; not settable by a cross-site HTML form).

### Endpoints

- Sign-in / anon register / account sign-in: set cookie for allowlisted web Origin; omit `refresh_token` from JSON for web
- `POST /refresh`: accept body **or** cookie; rotate; set cookie for web
- `POST /logout`: revoke presented refresh (cookie or body); clear cookie
- `POST /logout-all`: clear cookie after revoke

### CORS

Nginx + auth-svc: `Access-Control-Allow-Credentials: true` with explicit `Allow-Origin` (never `*`).

Cookie issuance allowlist (independent of ConfigMap lag): `https://veritasvpn.cloud`, `https://www.veritasvpn.cloud`, localhost:8000, plus `CORS_ORIGINS`.

### Rollout

1. Deploy `auth-svc` + reload nginx ConfigMap (`Access-Control-Allow-Credentials`).
2. Ensure live `CORS_ORIGINS` includes production website origins (k3s overlay already patches this).
3. Ship website JS (`credentials: 'include'`, no sessionStorage refresh).
4. Smoke: sign-in → `Set-Cookie: veritas_rt`; refresh with empty body + `X-Veritas-Client: web`; logout clears cookie; native clients still receive JSON `refresh_token`.
