# Implementation plan: audit backlog (all 28 items)

## Goals
Ship every agent-fully-doable audit item from the Dell + website/clients review. No multi-hop, dedicated IP, CF Tunnel dashboard, or store publishing.

## Phase 1 — Production correctness
| ID | Work | Done when |
|----|------|-----------|
| 1 | Pin auth/wg-manager/agent digests in k3s overlay to live images | overlay matches `kubectl get deploy/ds -o jsonpath=...image` |
| 2 | Set `BTCPAY_SERVER_URL` → mainnet Service; `BTCPAY_PUBLIC_URL` → mainnet hostname in base + k3s overlay | billing pod env points at mainnet |
| 3 | Blackbox + `BTCPayCustomerUnavailable` for `https://btcpay.veritasvpn.cloud/` (and keep mainnet probe) | alert fires on 502 |
| 4 | RegisterServer: upsert by public key / public IP so one online row per node; mark stale same-IP hostnames offline | single online server |

## Phase 2 — Resilience & hardening
| ID | Work |
|----|------|
| 5 | Agent SSE: reconnect as Warn with backoff; avoid ERROR on expected EOF |
| 6 | `veritas-firewall.sh`: place TCP stealth accept with WG accepts (match DS intent) |
| 7 | Backup: ensure mainnet dump; size-drop alert in alerts.yml |
| 8 | Prod nginx CORS: production origins only (overlay patch) |
| 9 | `STEALTH_PATH_PREFIX` via Secret; CM keeps enabled/host/port; patch wstunnel + wg-manager |

## Phase 3 — Website / account
| ID | Work |
|----|------|
| 10–11 | Fix `/install/` + `#features`; align downloads nav; copyright 2026 |
| 12 | Account: no Dell; feature lists; drop diskless overclaim |
| 13–14 | FAQ a11y; auth modal a11y; OG/canonical/sitemap/robots |
| 15–17 | Linux install stealth/KS; quarantine notes for v2; privacy/terms metadata |

## Phase 4 — Desktop
| ID | Work |
|----|------|
| 18–22 | Mode badge; stealth preflight; reconnect banner; KS copy; reconnect harden; Dell copy; Linux bundle docs |

## Phase 5 — Android
| ID | Work |
|----|------|
| 23–25 | KS guidance UI; auto-reconnect; reconnect banner; bypass picker polish |
| 26 | Remove Dell; Stealth: settings note (Linux) — full VpnService+wstunnel deferred as too large for one pass; FAQ stays accurate |

## Phase 6 — Docs
| ID | Work |
|----|------|
| 27–28 | Refresh IMPLEMENTATION_PLAN; FEATURES_SHIPPED.md; payment/dashboard plan prices |

## Phase 7 — Deploy
Sync Dell → apply CM/Secret/overlay → restart billing/wg-manager/nginx/prometheus as needed → commit + push.

## Out of scope (you)
Cloudflare Tunnel remap for `btcpay.veritasvpn.cloud`, warrant canary text, router rules.
