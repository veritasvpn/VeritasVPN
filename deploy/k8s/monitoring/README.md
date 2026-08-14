# VeritasVPN analytics

Private, aggregate-only operational analytics for the Dell production node.

## Access

- Tailscale: `http://100.100.36.115:30300`
- Cloudflare hostname: `https://analytics.veritasvpn.cloud` once the tunnel hostname and Access policy are enabled.
- Username: `admin`
- Password: stored only in the `monitoring/grafana-admin` Kubernetes secret.

Retrieve the initial password on the Dell:

```sh
kubectl -n monitoring get secret grafana-admin -o jsonpath='{.data.password}' | base64 -d
```

## Privacy boundary

The stack records aggregate service and infrastructure measurements. It does not ingest browsing history, DNS requests, destination domains, client IP addresses, account IDs, email addresses, or WireGuard public keys.

## Deploy

Secrets and the read-only PostgreSQL role are created separately and are never committed.

```sh
kubectl apply -k deploy/k8s/monitoring
```

Prometheus retains up to 21 days or 10 GB, whichever is reached first.
