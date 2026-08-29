# Cloudflare Tunnel Configuration

## Current setup

- `veritasvpn.cloud` and `www.veritasvpn.cloud` are served by Cloudflare Pages; they do not route to the Dell.
- `api.veritasvpn.cloud` routes through the K3s Cloudflare connector to ingress-nginx.
- `btcpay-mainnet.veritasvpn.cloud` routes to the mainnet BTCPay Kubernetes Service and is Access-gated.
- `analytics.veritasvpn.cloud` routes to Grafana and is Access-gated.
- The retired `btcpay.veritasvpn.cloud` testnet route must not be used by clients.

## Ingress Rules (configured in Cloudflare Zero Trust dashboard)

```
# Dell-origin routes
api.veritasvpn.cloud             → http://ingress-nginx-controller.ingress-nginx.svc.cluster.local:80
btcpay-mainnet.veritasvpn.cloud  → http://btcpayserver-mainnet.btcpay-mainnet.svc.cluster.local:49392
analytics.veritasvpn.cloud       → http://grafana.monitoring.svc.cluster.local:3000

# Catch-all (returns 404 for any other hostname)
*.veritasvpn.cloud       → 404 (catch-all)
```

## Cloudflare Access (Identity-Aware Proxy)

Apply Cloudflare Access policies to administrative routes:

```
# Protected routes (require authentication)
analytics.veritasvpn.cloud       → Allow: named administrator identity
btcpay-mainnet.veritasvpn.cloud  → Allow: named administrator identity
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

Only the K3s `cloudflared` deployment in `ingress-nginx` is supported. The Docker Compose fallback is retired and must remain stopped.

## Dell origin protection

- No HTTP management port on the Dell is exposed directly to the internet.
- Only UDP 51820 and stealth TCP 443 are router-forwarded today. The
  authenticated browser-proxy TCP 41080 edge remains closed while Chrome is paused.
- SSH, Kubernetes, Prometheus, Grafana origin, databases, and registries stay LAN/Tailscale-only.
