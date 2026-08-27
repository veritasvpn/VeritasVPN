# Audit fix implementation plan (2026-08-27)

Goal: clear all 12 agent-shippable Dell audit findings so a follow-up audit does not restate them.

## Order

| Phase | Items | Why first |
|-------|-------|-----------|
| A Ops signal | 1, 11, 2, 3, 5 | Stop false fails/alerts; add missing stealth probe |
| B Edge / site | 9, 10, 12 | CORS, CSP, copy — fast deploys |
| C Images | 8, 7, 4 | Alpine bump + wstunnel image + auth-svc digest pin |
| D Client artifact | 6 | Rebuild Linux AppImage/deb with stealth |

## Per item

### 1 + 11 — Health timer / retire testnet BTCPay
- Edit `deploy/systemd/veritas-k8s-health.sh`: drop hard fail on `https://btcpay.veritasvpn.cloud/`; stop requiring testnet (`btcpay`) rollouts; keep mainnet rollouts; make testnet Bitcoin RPC/port-forward optional/warn-only.
- Install script to `/usr/local/sbin/veritas-k8s-health` on Dell; run once → PASS.

### 2 — Real BTCPay monitoring
- `deploy/k8s/monitoring/prometheus.yml`: replace public Access-gated URL with in-cluster `http://btcpayserver-mainnet.btcpay-mainnet.svc.cluster.local:49392/` (and keep billing readyz).
- `alerts.yml`: `BTCPayUnavailable` matches new instance label.
- Apply monitoring ConfigMaps + reload Prometheus.

### 3 — Backup archive alert
- Lower `VeritasBackupArchiveTooSmall` threshold from 500000 → ~50000 (or content-aware later); update description.
- Reload rules; confirm alert clears.

### 5 — Stealth / wstunnel monitoring
- Add `tcp_connect` (and optional `tls_connect`) modules in `blackbox.yml`.
- Blackbox job targets `170.51.31.139:443`.
- Alert `StealthTunnelUnavailable` when probe fails.
- Optional: readiness on wstunnel DaemonSet after item 7.

### 9 — nginx CORS
- `deploy/k8s/base/nginx-configmap.yaml`: add `X-Veritas-Client` to `Access-Control-Allow-Headers`.
- Apply CM + rollout nginx; verify OPTIONS.

### 10 — Website CSP / ACAO
- `website/_headers`: drop `https://btcpay.veritasvpn.cloud` from `connect-src`; do not emit `Access-Control-Allow-Origin: *` (omit or pin site origin).
- Deploy via Pages (push) + Dell hostPath website already mounts repo.

### 12 — Docs / staging alignment
- Fix `website/account/README.md`, `website/install-staging/{index,downloads,windows}.html` to match FEATURES_SHIPPED (Chrome, Android, Linux live; Windows/macOS coming soon — no “Windows early access”).

### 8 — Alpine bump
- `services/{auth-svc,billing-svc,wg-manager,veritas-agent}/Dockerfile` and wstunnel image: `alpine:3.19` → `alpine:3.22`.
- Rebuild/push/redeploy affected images.

### 7 — Bake wstunnel image
- New `services/wstunnel/Dockerfile`: copy pinned `wstunnel` binary; Alpine 3.22.
- Update `deploy/k8s/base/wstunnel.yaml`: use image, drop hostPath; add readiness TCP check on 443.
- Pin digest in k3s overlay; roll DaemonSet.

### 4 — auth-svc GitOps pin
- After Alpine rebuild of auth-svc, update `deploy/k8s/overlays/k3s/kustomization.yaml` digest; `kubectl apply -k` (no bare `:latest`).

### 6 — Linux desktop with stealth
- On a machine with Node/Rust (or fix CI): `bundle-wg-linux.sh` + `bundle-wstunnel-linux.sh` + `tauri build`.
- Replace `website/downloads/veritasvpn-linux.{AppImage,deb}`; bump version note if needed.
- Smoke: binary contains wstunnel resource path.

## Verification (must pass before close)
1. `veritas-k8s-health` exits 0; no testnet public BTCPay check.
2. No firing `VeritasBackupArchiveTooSmall`; `BTCPayUnavailable` uses in-cluster target and is green when mainnet pods Ready.
3. Stealth blackbox probe succeeds; alert defined.
4. OPTIONS from website origin allows `X-Veritas-Client`.
5. Live CSP has no `btcpay.veritasvpn.cloud`.
6. Staging/account copy matches shipped platforms.
7. Deployed images Alpine ≥ 3.22; auth-svc digest matches kustomize; wstunnel DS has no hostPath.
8. Linux downloads newer than stealth commit and include stealth binary.
