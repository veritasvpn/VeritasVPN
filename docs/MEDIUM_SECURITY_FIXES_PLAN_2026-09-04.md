# Implementation plan: remaining Medium security findings (2026-09-04)

Source: live VPN + website audit canvas after account-delete teardown (`eae284d` / digests `70cec1b`).
Goal: close or explicitly bound each remaining Medium with code/config + a check.

| ID | Area | Finding | Decision | Fix | Verification |
|----|------|---------|----------|-----|--------------|
| M1 | Website | Check API rate limit fails **open** if Cache API errors | Fix | Fail **closed** on Cloudflare edge (`request.cf` present) → 503 | Unit-style assertion in plan notes; manual: Functions still 200 when healthy |
| M2 | Website | Reset/verify accept `?token=` query | Fix | Fragment-only (`#token=`); ignore query | JS no longer reads `location.search` for token; emails already use `#token=` |
| M3 | Ops | WAN GHA fails: `VERITAS_PUBLIC_IP` unset | Fix | Set repo Actions variable to node egress IP; document in ops script header | Workflow_dispatch PASS for SSH+metrics closed |
| M4 | VPN | `stealth_path_prefix` on GET peer | Fix | Keep on **create peer** only; omit from GET-by-id | Handler test / grep: GET peer JSON has no `stealth_path_prefix` |
| M5 | VPN | NFT isolation IPv4-centric | Fix | Drop IPv6 ULA/link-local/loopback from `wg` forward; add known DoH IPv6 anycast set | `go test` nftables expects `ip6 daddr` drops + `doh_v6` |
| M6 | VPN | Custom DoH can bypass Shield | Bound | Expand known DoH hostnames + IPv6 anycast; keep honesty docs (full custom DoH = Phase 3) | doh_bypass + nftables tests; `docs/DNS_APP_DOH_BLOCKING.md` updated |

## Out of scope (explicit)

- Phase 3 standalone DoH / Chrome DNS / full AV
- Rewriting metrics to iface-scoped binds (Prometheus hostNetwork scrape still needs non-loopback; WAN drop + M3 probe remain the control)
- Git-history IP scrub, Dependabot/CodeQL backlog, password rotation (ops hygiene, not this PR)

## Done when

- [x] M1–M5 implemented and tested
- [x] M6 bounded (lists + docs); residual called out as Phase 3
- [x] `VERITAS_PUBLIC_IP` set; WAN verify workflow green (dispatch after push)
- [ ] Changes live (Pages + agent/wg as needed)

## Deploy notes

- Website/Functions: push → Cloudflare Pages
- Agent firewall/DoH: rebuild + roll `veritas-agent`
- Stealth GET omit: rebuild + roll `wg-manager` (clients use create response for stealth)
- No auth-svc change required for M2 (emails already use `#token=`)
