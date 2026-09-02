# Free privacy check tools (`/check/`)

Static pages under `website/check/` plus Cloudflare Pages Functions under
`website/functions/api/check/`.

| Path | Role |
|------|------|
| `/check/` | Hub |
| `/check/ip.html` | Public IP via `/api/check/ip` |
| `/check/dns.html` | DNS leak probe via session + edns.ip-api.com hostnames |
| `/check/vpn.html` | IP + WebRTC + IPv6 |
| `/check/browser.html` | Local browser signals |
| `/check/breach.html` | Email breach corpus check via `/api/check/breach` |
| `/check/report.html` | Combined report |

## Privacy notes

- No account required.
- IP responses use Cloudflare request metadata and are not stored as profiles.
- DNS leak correlation currently uses short-lived hostnames on `edns.ip-api.com`
  (disclosed in UI + Privacy Policy). A self-hosted authoritative collector can
  replace this later.
- Breach checks proxy XposedOrNot; Veritas does not store the submitted email.
