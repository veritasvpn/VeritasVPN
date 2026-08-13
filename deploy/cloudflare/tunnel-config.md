# Cloudflare Tunnel Configuration

## Current Setup

The production Cloudflare Zero Trust tunnel connects:
- `veritasvpn.cloud` → Nginx (via K8s ingress or compose fallback)
- `www.veritasvpn.cloud` → same
- `btcpay.veritasvpn.cloud` → BTCPay Server

## Ingress Rules (configured in Cloudflare Zero Trust dashboard)

```
# Public routes
veritasvpn.cloud         → http://nginx:80
www.veritasvpn.cloud     → http://nginx:80
btcpay.veritasvpn.cloud  → http://btcpayserver:49392

# Catch-all (returns 404 for any other hostname)
*.veritasvpn.cloud       → 404 (catch-all)
```

## Cloudflare Access (Identity-Aware Proxy)

Apply Cloudflare Access policies to administrative routes:

```
# Protected routes (require authentication)
api.veritasvpn.cloud/admin/* → Allow: email ending in @veritasvpn.cloud
```

## WAF Rules (configured in Cloudflare dashboard)

```
# Rate limiting
/auth/login         → 5 req/5min per IP
/auth/register      → 3 req/hour per IP
/api/v1/wg/peers    → 10 req/min per IP
/api/v1/billing/*   → 10 req/min per IP

# Block rules
- Block requests with no User-Agent
- Block requests to internal paths (/admin/* without Access token)
```

## Tunnel Connector

Only ONE tunnel connector should run at a time:
1. **Production**: K8s cloudflared deployment (ingress-nginx namespace)
2. **Fallback**: Docker compose `--profile tunnel-fallback` cloudflared service

Both must NOT run simultaneously with the same tunnel token.

## Pi Origin Protection

- No HTTP ports on the Pi are exposed to the internet
- The Pi's public IP should NOT resolve any hostname
- Verify: `curl -H "Host: veritasvpn.cloud" http://<pi-public-ip>` should fail
