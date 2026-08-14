# VeritasVPN — Kubernetes Deployment

k3s-based deployment for VeritasVPN backend services and BTCPay Server.

## Prerequisites

- k3s cluster (single or multi-node)
- `kubectl` configured
- Docker (for building service images)
- WireGuard kernel module on VPN nodes

## Quick Start

```bash
# 1. Build and push all service images to local registry
REGISTRY=localhost:31500 TAG=latest bash deploy/k8s/scripts/push-images.sh

# 2. Create secrets (required before first deploy)
cp deploy/k8s/base/secrets.example.yaml deploy/k8s/base/secrets.yaml
cp deploy/k8s/btcpay/secrets.example.yaml deploy/k8s/btcpay/secrets.yaml
# Edit both files and replace CHANGE_ME with real values

# 3. Create cloudflared tunnel token secret
kubectl create namespace ingress-nginx
kubectl create secret generic cloudflared-token -n ingress-nginx --from-literal=token=YOUR_TOKEN

# 4. Apply the dev overlay
bash deploy/k8s/scripts/apply.sh dev

# 5. Check status
make k8s-status
```

## Architecture

```
Namespace: veritas                      Namespace: btcpay
───────────────                         ───────────────
postgres (STS)                          postgres-btcpay (STS)
redis (Deploy, x2 in prod)              bitcoind (STS)
nats (STS)                              nbxplorer (STS)
auth-svc (Deploy)                       btcpayserver (Deploy)
wg-manager (Deploy)                     Bitcoin-only checkout
billing-svc (Deploy)                    archived wallet PVC (offline)
veritas-agent (DaemonSet, hostNetwork)
veritas-proxy (Deploy)                  Namespace: ingress-nginx
nginx (Deploy)                          ─────────────────────
ingress                                 cloudflared (Deploy)
NetworkPolicies (deny-all + allow-list)
```

## Namespaces

| Namespace | Purpose |
|-----------|---------|
| `veritas` | Core backend services (auth, WG manager, billing, nginx, proxy) |
| `btcpay` | BTCPay Server stack (bitcoind, nbxplorer, btcpayserver, postgres) |
| `ingress-nginx` | Ingress controller and Cloudflare tunnel |

## Secrets

Secrets are NOT committed to Git. Before deploying:

1. Copy example files and fill in real values:
   - `deploy/k8s/base/secrets.example.yaml` → `secrets.yaml`
   - `deploy/k8s/btcpay/secrets.example.yaml` → `secrets.yaml`
2. Create the cloudflared token secret:
   ```bash
   kubectl -n ingress-nginx create secret generic cloudflared-token --from-literal=token=...
   ```

The Kustomize base references `secrets.yaml` which must exist locally but is gitignored.

## Overlays

| Overlay | Description |
|---------|-------------|
| `dev` | amd64 node selector, mock BTCPay enabled, debug logging |
| `prod` | production env, info logging, mock BTCPay disabled, Redis x2 replicas, readOnlyRootFilesystem, runAsNonRoot, cap drop ALL |

## Security Hardening (prod overlay)

- **Stateless services** (auth-svc, wg-manager, billing-svc, nginx, veritas-proxy): readOnlyRootFilesystem, runAsNonRoot, allowPrivilegeEscalation: false, capabilities.drop: ["ALL"]
- **PostgreSQL**: runAsUser 70 (postgres), capabilities.drop: ALL
- **NATS**: runAsNonRoot, capabilities.drop: ALL
- **Redis**: runAsNonRoot (uid 999), requirepass authentication, capabilities.drop: ALL
- **veritas-agent**: privileged (required for WireGuard/netlink), hostNetwork, health probes on wg0 + UDP 51820

## Network Policies

All ingress denied by default in `veritas` namespace. Specific allow rules:
- nginx ← ingress-nginx namespace (port 8000)
- postgres ← auth-svc, wg-manager, billing-svc (port 5432)
- redis ← auth-svc, wg-manager (port 6379)
- NATS ← auth-svc, wg-manager, billing-svc (port 4222)
- auth-svc/wg-manager/billing-svc ← nginx (port 8080)
- agent (hostNetwork) can reach wg-manager via cluster DNS on port 8080

## BTCPay Server

```bash
kubectl apply -k deploy/k8s/btcpay/
```

BTCPay runs in its own namespace with a pruned Bitcoin mainnet node.

## veritas-agent DaemonSet

Runs only on nodes labeled `veritas-vpn-node=true`:

```bash
kubectl label node <node-name> veritas-vpn-node=true
```

Requirements:
- WireGuard kernel module installed
- `/opt/veritasvpn/data/wireguard/` with server private key
- hostNetwork + privileged security context
- Health probes verify wg0 interface and UDP 51820 listener

## Cloudflare Tunnel

The cloudflared Deployment in `ingress-nginx` namespace tunnels traffic to the ingress-nginx controller:

```bash
kubectl apply -f deploy/k8s/ingress-nginx/cloudflared.yaml
```

Cloudflare Zero Trust dashboard must be configured with:
- `veritasvpn.cloud` → `http://ingress-nginx-controller.ingress-nginx.svc.cluster.local:80`
- `www.veritasvpn.cloud` → same

## Building Images

```bash
# All service images
REGISTRY=localhost:31500 bash deploy/k8s/scripts/push-images.sh

# Single service
docker build -t localhost:31500/auth-svc:latest -f services/auth-svc/Dockerfile .
docker push localhost:31500/auth-svc:latest
```

## Scripts

| Script | Purpose |
|--------|---------|
| `scripts/apply.sh [dev|prod]` | Apply a Kustomize overlay |
| `scripts/push-images.sh` | Build and push all service images |

## Migrating from Docker Compose

See `deploy/README.md` for the migration guide and `deploy/recovery/recovery-runbook.md` for disaster recovery.
