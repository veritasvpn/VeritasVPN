# Testing plan: residual public-repo findings (R1–R4)

Audit source: re-audit after `89d9901` (canvas `github-reaudit-after-hardening`).
Goal: close each residual with a code/config change **and** an automated or scripted check.

| ID | Sev | Finding | Fix | Test / verification |
|----|-----|---------|-----|---------------------|
| R1 | High | `envRequired` forces JWT **private** key on all `config.Load()` callers | Make `JWT_ED25519_PRIVATE_KEY` and `JWT_ACTIVE_KEY_ID` optional in `Load()`; auth-svc keeps fail-closed mint checks | `go test ./lib/config` — Load succeeds with public keys only; empty public keys still exits |
| R2 | Medium | Compose/legacy nginx proxies `/api/v1/agents/` | Match k8s: `return 404` in `website/nginx.conf` + `deploy/nginx/nginx.prod.conf` | `rg`/CI grep: no `proxy_pass` under agents locations; optional `nginx -t` if available |
| R3 | Medium | `STEALTH_PATH_PREFIX` on `GET /api/v1/wg/servers` | Omit prefix from ListServers; keep on create/get peer | Unit test: list-servers payload must not contain `stealth_path_prefix`; create/get still may |
| R4 | Medium | Metrics `0.0.0.0` (firewall-dependent) | Keep cluster scrape bind (localhost breaks Prometheus); add WAN probe script + wire into verify-wan workflow | `deploy/ops/verify-metrics-wan.sh` expects TCP fail on `:9090`/`:9100`; GHA job off-LAN |

## Out of scope

- Rewriting agent metrics to interface-scoped binds (needs iface discovery + scrape redesign)
- Full e2e against live JWT mint without private key on Dell (covered by unit + next roll note)

## Done when

- [x] R1 tests green; wg-manager/billing can Load without private key
- [x] R2 agents locations return 404 in compose/prod nginx configs
- [x] R3 ListServers omits stealth path; test asserts it
- [x] R4 verify script + workflow exist; local dry-run documents expected fail-closed behavior
