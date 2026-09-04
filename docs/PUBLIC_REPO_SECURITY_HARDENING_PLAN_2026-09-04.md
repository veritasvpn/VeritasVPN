# Implementation plan: public-repo security hardening (2026-09-04)

Audit source: pre-announce review of `veritasvpn/VeritasVPN` (already **public**).
Goal: close Critical/High findings before amplifying the share tonight. No exploit PoCs.

## Scope

| ID | Severity | Finding | Approach |
|----|----------|---------|----------|
| C1 | Critical | Prod IP / LAN / `/home/jpg` / Dell topology in tree | Standardize on `/opt/veritasvpn`; replace host specifics with env/`REPLACE_ME_*` placeholders; redact docs |
| H1 | High | Linux desktop NOPASSWD on user-writable `~/.veritasvpn/*.sh` | **Remove** passwordless sudoers install; always elevate via `pkexec` until a root-owned binary helper exists |
| H2 | High | Unauthenticated registry NodePort 31500 | Remove `registry-hostlocal` NodePort; keep ClusterIP; document loopback `kubectl port-forward` for pushes |
| H3 | High | Empty `AGENT_AUTH_TOKEN` accepted | Make `envRequired` fail-closed; fatal if empty after load in wg-manager |
| H4 | High | `AgentTokenHash` on GET peer | `json:"-"` on hash fields; sanitize GET peer `server` like `ListServers` |
| M2 | Medium | Non-constant-time register token compare | `subtle.ConstantTimeCompare` on bootstrap token |
| M4 | Medium | Weak docker-compose defaults | Require passwords via `${VAR:?}`; do not default Redis/NATS open |
| L2 | Low | Stale warrant canary | Refresh dates in `website/canary.txt` |

## Out of scope (tonight)

- Full rewrite of Linux bring-up as a signed root binary (follow-up)
- Registry htpasswd / mTLS (NodePort removal is enough for now)
- Live cluster apply / image roll (code + manifests only unless you ask)
- Force-push history rewrite to erase past IP leaks (forward scrub only)
- GitHub UI toggle for Dependabot security updates (manual)

## Order of work

1. Plan doc (this file)
2. H3 + H4 + M2 (Go: config + wg-manager) — smallest blast radius, highest value
3. H2 registry NodePort removal + doc/script pointers
4. H1 desktop sudoers removal
5. C1 path/IP scrub across deploy/docs/workflows
6. M4 compose + L2 canary
7. Targeted tests (`go test` for touched packages)

## Done when

- [x] Empty `AGENT_AUTH_TOKEN` cannot start wg-manager / fails config load
- [x] GET `/api/v1/wg/peers/{id}` does not include agent token hash
- [x] No NodePort 31500 Service in base manifests
- [x] Desktop bring-up no longer writes NOPASSWD sudoers for home scripts
- [x] Public tree has no `/home/jpg` or hardcoded `170.51.31.139` (placeholders/env only)
- [x] Canary next-update date is current
- [x] Touched Go tests pass
