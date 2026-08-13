# VeritasVPN — Kubernetes Migration Plan

> **Goal:** Migrate the existing Docker Compose deployment to Kubernetes.
> **Strategy:** Start with a development-grade local K8s cluster, then evolve to production.

---

## Table of Contents

1. [Current State Analysis](#1-current-state-analysis)
2. [Kubernetes Distribution Selection](#2-kubernetes-distribution-selection)
3. [Cluster Architecture](#3-cluster-architecture)
4. [Workload Migration Mapping](#4-workload-migration-mapping)
5. [Implementation Phases](#5-implementation-phases)
6. [File Structure (Proposed)](#6-file-structure-proposed)
7. [CI/CD Changes](#7-cicd-changes)
8. [Risks & Decisions](#8-risks--decisions)

---

## 1. Current State Analysis

### 1.1 Services Under Docker Compose

| Service | Image | Ports | Privileges | Persistence | Dependencies |
|---------|-------|-------|------------|-------------|--------------|
| **postgres** | postgres:16-alpine | 5432 | — | pgdata volume | — |
| **redis** | redis:7-alpine | 6379 | — | ephemeral | — |
| **nats** | nats:2.10-alpine | 4222, 8222 | — | ephemeral (JetStream) | — |
| **auth-svc** | built (Go) | 8081:8080 | — | — | postgres, redis, nats |
| **wg-manager** | built (Go) | 8082:8080 | — | — | postgres, redis, nats |
| **billing-svc** | built (Go) | 8083:8080 | — | — | postgres, nats |
| **veritas-agent** | built (Go) | host | privileged, NET_ADMIN, NET_RAW | /etc/wireguard | wg-manager |
| **veritas-proxy** | built (GOST) | 1080 | — | — | WireGuard peers |
| **nginx** | nginx:1.27-alpine | 8000 | — | static files (ro) | auth-svc, wg-manager, billing-svc |
| **cloudflared** | cloudflare/cloudflared | host | — | config (ro) | nginx |

### 1.2 BTCPay Server (Separate Compose)

| Service | Image | Notes |
|---------|-------|-------|
| postgres-btcpay | postgres:16-alpine | Separate PG instance |
| bitcoind | btcpayserver/bitcoin:28.1 | Full node (pruned) |
| nbxplorer | nicolasdorier/nbxplorer:2.6.9 | Indexer |
| btcpayserver | btcpayserver/btcpayserver:2.1.3 | Payment processor |

### 1.3 Key Challenges for K8s Migration

1. **veritas-agent**: Requires `privileged: true`, `NET_ADMIN`, `NET_RAW`, host networking, and access to WireGuard kernel module. This is the most K8s-unfriendly component.
2. **NATS JetStream**: Requires persistent storage for durable streams. Currently ephemeral.
3. **PostgreSQL**: Currently uses `docker-entrypoint-initdb.d` to auto-run SQL migrations from local directories. Needs a migration init container or job.
4. **cloudflared**: Uses host networking for tunnel. Can be replaced with a simple Deployment using ClusterIP.
5. **BTCPay cross-network**: billing-svc connects to `btcpay_default` network. Needs K8s NetworkPolicy or simply be in the same namespace.
6. **nginx static files**: Mounts `./website` read-only. Needs a ConfigMap volume or a custom image.

---

## 2. Kubernetes Distribution Selection

### Recommendation: **k3s**

| Criteria | k3s | microk8s | minikube | kind |
|----------|-----|----------|----------|------|
| Resource light | Yes | Yes | Medium | Minimal |
| Production-ready | Yes | Yes | No | No |
| Built-in ingress | Traefik | Optional | N/A | N/A |
| Local path provisioner | Yes | hostpath | hostpath | hostpath |
| Supports hostNetwork + privileged | Yes | Yes | Yes | Limited (Docker-in-Docker) |
| Multi-node support | Yes | Yes | Yes | Yes (but Docker-based) |

**Plan**: Use **k3s** for both development and production. For dev, a single-node cluster. For production, multi-node with VPN edge nodes as cluster members.

---

## 3. Cluster Architecture

### 3.1 Development (Single Node — k3s on your dev machine)

```
┌─────────────────────────────────────────────┐
│                  k3s (single node)           │
│                                              │
│  Namespace: veritas                          │
│  ┌──────────┐  ┌──────────┐  ┌───────────┐  │
│  │ postgres │  │  redis   │  │   nats    │  │
│  │  (STS)   │  │ (Deploy) │  │ (Deploy)  │  │
│  └──────────┘  └──────────┘  └───────────┘  │
│                                              │
│  ┌──────────┐  ┌──────────┐  ┌───────────┐  │
│  │auth-svc  │  │wg-mgr    │  │billing-svc│  │
│  │(Deploy)  │  │(Deploy)  │  │(Deploy)   │  │
│  └──────────┘  └──────────┘  └───────────┘  │
│                                              │
│  ┌──────────┐  ┌──────────────┐             │
│  │  nginx   │  │veritas-proxy │             │
│  │(Deploy)  │  │  (Deploy)    │             │
│  └──────────┘  └──────────────┘             │
│                                              │
│  Namespace: btcpay                           │
│  ┌──────────┐  ┌──────────┐                │
│  │btcpay-pg │  │ btcpaysvr │                │
│  │  (STS)   │  │(Deploy)   │                │
│  └──────────┘  └──────────┘                │
│  ┌──────────┐  ┌──────────┐                │
│  │ bitcoind │  │nbxplorer │                │
│  │(Deploy)  │  │(Deploy)  │                │
│  └──────────┘  └──────────┘                │
│                                              │
│  veritas-agent runs outside K8s on edge nodes│
│  (or as DaemonSet with hostNetwork on VPN VMs)│
└─────────────────────────────────────────────┘
```

### 3.2 Production (Multi-Node)

```
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│  k3s server  │   │ VPN Node 1   │   │ VPN Node 2   │
│  (control    │   │ (k3s agent)  │   │ (k3s agent)  │
│   plane +    │   │              │   │              │
│   workloads) │   │ veritas-agent│   │ veritas-agent│
│              │   │ (DaemonSet)  │   │ (DaemonSet)  │
│ postgres     │   │ WireGuard    │   │ WireGuard    │
│ redis, nats  │   │ kernel mod   │   │ kernel mod   │
│ auth-svc     │   │              │   │              │
│ wg-manager   │   │ veritas-proxy│   │ veritas-proxy│
│ billing-svc  │   │              │   │              │
│ nginx        │   │              │   │              │
│ cloudflared  │   │              │   │              │
│ btcpayserver │   │              │   │              │
└──────────────┘   └──────────────┘   └──────────────┘
```

---

## 4. Workload Migration Mapping

### 4.1 Stateless Services → Deployments

| Docker Compose | K8s Resource | Replicas | Reasoning |
|----------------|-------------|----------|-----------|
| auth-svc | Deployment | 1-3 | Stateless, horizontally scalable |
| wg-manager | Deployment | 1-3 | Stateless, scalable |
| billing-svc | Deployment | 1-2 | Stateless, scalable |
| nginx | Deployment | 1 | Stateless, serves static + proxies |
| veritas-proxy | Deployment | 1 | Stateless SOCKS5 proxy |
| cloudflared | Deployment | 1 | Tunnel daemon, no hostNetwork needed with ClusterIP services |

### 4.2 Stateful Services → StatefulSets

| Docker Compose | K8s Resource | PVC Size | Notes |
|----------------|-------------|----------|-------|
| postgres | StatefulSet | 20Gi | Needs migration init container |
| nats (with JetStream) | StatefulSet | 10Gi | JetStream requires persistent storage |
| btcpayserver postgres | StatefulSet | 10Gi | Separate namespace |
| bitcoind | StatefulSet | 50Gi+ | Can be large, needs prune settings |

### 4.3 Stateless Stateful → Deployment with PVC

| Docker Compose | K8s Resource | Notes |
|----------------|-------------|-------|
| redis | Deployment (1 replica) | Can switch to StatefulSet if persistence needed |

### 4.4 Node-Specific Workloads → DaemonSet

| Docker Compose | K8s Resource | Notes |
|----------------|-------------|-------|
| veritas-agent | DaemonSet | `hostNetwork: true`, `privileged: true`, nodeSelector for VPN nodes only |

### 4.5 Configuration & Secrets

| Current (.env) | K8s Resource | Notes |
|----------------|-------------|-------|
| JWT_SECRET | Secret | Opaque, generated once |
| AGENT_AUTH_TOKEN | Secret | Opaque, shared with agent DaemonSet |
| DB_PASSWORD | Secret | Opaque, used by postgres + all services |
| .env vars | ConfigMap | Non-secret config values |
| nginx.conf | ConfigMap | Mounted as volume |
| website/ static files | ConfigMap or init container | Could also build into custom nginx image |
| cloudflared config | Secret | Tunnel credentials |

### 4.6 Networking

| Docker Compose | K8s Equivalent |
|----------------|---------------|
| docker compose default network | ClusterIP Services — internal DNS (`svc.namespace.svc.cluster.local`) |
| `depends_on` | Startup probes, `initContainers`, or readiness probes |
| Host port mappings | NodePort / LoadBalancer Service (dev) or ClusterIP + Ingress (prod) |
| `btcpay_default` external network | Cross-namespace service via `<svc>.<ns>.svc.cluster.local` |

### 4.7 Ingress & External Access

| Current | K8s Equivalent |
|---------|---------------|
| Cloudflare Tunnel → nginx:8000 | Ingress + external-dns with Cloudflare, or continue using cloudflared as a sidecar |
| nginx reverse proxy | Ingress rules (Traefik/k3s built-in) replacing the nginx container entirely, OR keep nginx as a pod behind the ingress |
| veritas-proxy :1080 | ClusterIP Service (internal only) |

**Recommendation**: Keep nginx for now (it has custom CORS logic), place it behind k3s's built-in Traefik ingress. Alternatively, move the routing logic into Traefik IngressRoute and eliminate the nginx pod.

---

## 5. Implementation Phases

### Phase 1: Local Dev Cluster + Core Infrastructure (Day 1-2)

**Goal**: Get a k3s cluster running locally with PostgreSQL, Redis, and NATS.

1. **Install k3s** on your dev machine:
   ```bash
   curl -sfL https://get.k3s.io | sh -
   ```

2. **Create namespace:**
   ```bash
   kubectl create namespace veritas
   ```

3. **Deploy PostgreSQL** as a StatefulSet with:
   - Secret for `DB_PASSWORD`
   - PVC for `/var/lib/postgresql/data`
   - Init container that runs SQL migrations from a ConfigMap (or from the repo-mounted volume)
   - ClusterIP Service named `postgres`

4. **Deploy Redis** as a Deployment:
   - ClusterIP Service named `redis`

5. **Deploy NATS** as a StatefulSet:
   - PVC for JetStream store
   - ClusterIP Service named `nats` (ports 4222, 8222)

6. **Verify**: `kubectl get pods -n veritas` shows all 3 running healthy.

**Deliverables:**
- `deploy/k8s/base/postgres.yaml`
- `deploy/k8s/base/redis.yaml`
- `deploy/k8s/base/nats.yaml`
- `deploy/k8s/base/secrets.yaml`
- `deploy/k8s/base/kustomization.yaml`

### Phase 2: Backend Services (Day 2-3)

**Goal**: Deploy auth-svc, wg-manager, billing-svc as K8s Deployments.

1. **Build and push images** to a registry (Docker Hub, ghcr.io, or local k3s embedded registry).
2. **Create Deployment manifests** for each service with:
   - Environment variables from ConfigMap + Secrets
   - Health checks (readiness/liveness probes)
   - Resource limits/requests
   - Internal ClusterIP Service per service
3. **Run database migrations**: Change migration init from Docker Compose volumes to a K8s Job or init container that applies SQL files.
4. **Test inter-service communication**: auth-svc ↔ wg-manager ↔ billing-svc via NATS and direct HTTP/gRPC.

**Deliverables:**
- `deploy/k8s/base/auth-svc.yaml` (Deployment + Service)
- `deploy/k8s/base/wg-manager.yaml`
- `deploy/k8s/base/billing-svc.yaml`
- `deploy/k8s/base/migrations-job.yaml`
- `deploy/k8s/base/configmap.yaml`

### Phase 3: Nginx + Ingress (Day 3)

**Goal**: Expose the website and API publicly.

1. **Option A (recommended):** Convert nginx config to Traefik IngressRoute CRD (k3s built-in). Move static website files to a ConfigMap or build into a separate image.
2. **Option B:** Deploy nginx as a K8s Deployment with website files via ConfigMap, then expose via Traefik Ingress.
3. **Configure ingress** with proper CORS and TLS (k3s automatically uses Let's Encrypt via cert-manager or Traefik's built-in ACME or Cloudflare origin certs).
4. **Set up cloudflared** as a Deployment in the cluster (no host network needed — it connects to ClusterIP services). Alternatively, drop cloudflared and let Traefik terminate TLS directly with Cloudflare DNS + external-dns.

**Recommendation:** Keep cloudflared as a Deployment for now (it's already configured), and place Traefik in front of nginx. Long term, consider dropping cloudflared in favor of Traefik + external-dns + Cloudflare origin certificates.

**Deliverables:**
- `deploy/k8s/base/nginx.yaml` (if keeping nginx)
- `deploy/k8s/base/cloudflared.yaml`
- `deploy/k8s/base/ingress.yaml` or `ingressroute.yaml`

### Phase 4: BTCPay Server (Day 3-4)

**Goal**: Migrate the BTCPay Server stack from its separate docker-compose to the K8s cluster.

1. **Create `btcpay` namespace** for isolation.
2. **Deploy bitcoind, nbxplorer, postgres-btcpay, btcpayserver** using the same pattern as Phase 1-2.
3. **Bitcoind needs substantial storage** (even pruned, 10-20Gi). Use a PVC.
4. **Cross-namespace access**: billing-svc in `veritas` namespace accesses btcpayserver in `btcpay` namespace via `btcpayserver.btcpay.svc.cluster.local:49392`.

**Deliverables:**
- `deploy/k8s/btcpay/bitcoind.yaml`
- `deploy/k8s/btcpay/nbxplorer.yaml`
- `deploy/k8s/btcpay/postgres-btcpay.yaml`
- `deploy/k8s/btcpay/btcpayserver.yaml`
- `deploy/k8s/btcpay/kustomization.yaml`

### Phase 5: veritas-agent DaemonSet (Day 4)

**Goal**: Run veritas-agent on VPN edge nodes via DaemonSet.

This is the most complex migration. The agent needs:
- `hostNetwork: true`
- `privileged: true`
- `NET_ADMIN` + `NET_RAW` capabilities
- Access to `/etc/wireguard/private.key`
- communication with wg-manager

**Approach:**
1. Create a DaemonSet with `nodeSelector` to target only VPN nodes (label: `vpn-node=true`).
2. Mount `/etc/wireguard` as hostPath.
3. Secure the agent auth token via Secrets.
4. Since k3s agents can run on VPN edge nodes, the agent DaemonSet will only deploy on labeled nodes.

**Deliverables:**
- `deploy/k8s/base/veritas-agent-daemonset.yaml`

### Phase 6: veritas-proxy + Browser Extension (Day 5)

**Goal**: Deploy the GOST SOCKS5 proxy.

This is straightforward — a single Deployment + ClusterIP Service.

**Deliverables:**
- `deploy/k8s/base/veritas-proxy.yaml`

### Phase 7: Tooling & Polish (Day 5-6)

1. **Skaffold / Tilt / DevSpace** for local development with hot reload.
2. **Helm chart** (optional but recommended for production) wrapping all manifests.
3. **Update the Makefile** with `make k8s-up`, `make k8s-down`, `make k8s-logs`.
4. **Update README** with K8s setup instructions.
5. **Monitoring**: Deploy Prometheus + Grafana (kube-prometheus-stack Helm chart) or keep it simple with k3s's built-in metrics.

---

## 6. File Structure (Proposed)

```
deploy/k8s/
├── base/                          # Common manifests (dev + prod)
│   ├── kustomization.yaml          # Kustomize base
│   ├── namespace.yaml
│   ├── secrets.yaml               # Opaque Secrets (JWT_SECRET, etc.)
│   ├── configmap.yaml             # Non-sensitive config
│   ├── postgres.yaml              # StatefulSet + PVC + Service
│   ├── redis.yaml                 # Deployment + Service
│   ├── nats.yaml                  # StatefulSet + Service
│   ├── auth-svc.yaml              # Deployment + Service
│   ├── wg-manager.yaml            # Deployment + Service
│   ├── billing-svc.yaml           # Deployment + Service
│   ├── veritas-agent.yaml         # DaemonSet (VPN nodes)
│   ├── veritas-proxy.yaml         # Deployment + Service
│   ├── nginx.yaml                 # Deployment + Service
│   ├── cloudflared.yaml           # Deployment
│   ├── ingress.yaml               # Ingress / IngressRoute
│   └── migrations.yaml            # Job (run once)
│
├── overlays/
│   ├── dev/                       # Development overlay
│   │   ├── kustomization.yaml
│   │   └── patches/               # Dev-specific patches (mock btcpay, log level, etc.)
│   └── prod/                      # Production overlay
│       ├── kustomization.yaml
│       └── patches/               # Prod-specific patches (real BTCPay, higher replicas)
│
├── btcpay/                        # BTCPay Server manifests (if co-located)
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   ├── secrets.yaml
│   ├── postgres-btcpay.yaml
│   ├── bitcoind.yaml
│   ├── nbxplorer.yaml
│   └── btcpayserver.yaml
│
├── scripts/
│   ├── k3s-install.sh             # Bootstrap script
│   ├── push-images.sh             # Build & push Docker images to registry
│   └── apply.sh                   # kubectl apply -k overlays/dev
│
└── README.md                      # K8s setup documentation
```

---

## 7. CI/CD Changes

### 7.1 Image Registry

Currently, the CI builds Go binaries. With K8s, you need to build and push container images.

**Add to `.github/workflows/ci.yml`:**

```yaml
build-and-push-images:
  name: Build & Push Images
  runs-on: ubuntu-latest
  strategy:
    matrix:
      service: [auth-svc, wg-manager, billing-svc, veritas-agent, veritas-proxy]
  steps:
    - uses: actions/checkout@v4
    - name: Build & push
      uses: docker/build-push-action@v5
      with:
        context: .
        file: services/${{ matrix.service }}/Dockerfile  # or containers/ for proxy
        push: true
        tags: ghcr.io/veritasvpn/${{ matrix.service }}:${{ github.sha }}
```

**Or use `docker compose build` + push** instead of individual matrix builds.

### 7.2 Registry Options

| Option | Pros | Cons |
|--------|------|------|
| **ghcr.io** (GitHub Container Registry) | Free for public repos, integrated with GitHub Actions | — |
| Docker Hub | Universal | Rate limits on free tier |
| k3s embedded (dev only) | Zero config, instant | No push/pull needed, `imagePullPolicy: IfNotPresent` |

**Recommendation**: Use **ghcr.io** for CI-built images. For local dev, use k3s's embedded containerd and `crictl` imports, or a local registry.

---

## 8. Risks & Decisions

### 8.1 Decision Points

| Question | Recommendation | Rationale |
|----------|---------------|-----------|
| **Kustomize vs Helm?** | Start with Kustomize, add Helm later | Simpler for this scale. Helm when you need to distribute/template for users. |
| **PostgreSQL: StatefulSet vs CloudNativePG operator?** | StatefulSet initially | Simpler, fewer dependencies. Operator adds automated backups and failover; adopt in production. |
| **NATS: Solo StatefulSet or NATS cluster?** | Single replica StatefulSet | Single-node is fine for MVP. Cluster via K8s operator (nats-io/nack) if HA needed. |
| **nginx: Keep or replace with Traefik?** | Keep in Phase 3, migrate to Traefik IngressRoute rules later | Reduces moving parts. But long-term, native Traefik is cleaner. |
| **cloudflared: Keep or drop?** | Keep as Deployment | Already configured. Plan to evaluate Traefik ACME + external-dns later. |
| **veritas-agent outside K8s?** | DaemonSet on labeled nodes | Fully managed by K8s, unified deployment. Falls back to systemd on non-K8s edge nodes if needed. |
| **BTCPayServer migration** | Separate namespace, same cluster | Easier than running 2 clusters. Can be split to its own cluster later. |

### 8.2 Key Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| veritas-agent privileged access breaks on some K8s setups | High — VPN won't work | Use nodeSelector + tolerations. Test thoroughly on target VPS. |
| PostgreSQL migration failure (init container format differs from Docker) | Medium — data schema wrong | Test migration Job extensively. Use same SQL files. |
| Network complexity (NATS JetStream, cross-namespace BTCPay) | Medium — service communication fails | Validate with `kubectl port-forward` and integration tests. |
| Cloudflare tunnel credentials exposed in ConfigMap/Secret | Medium — security leak | Use Sealed Secrets or External Secrets Operator, not plain-text secrets in git. |
| Local storage on k3s (default local-path provisioner) is not production-grade | Medium — data loss on node failure | For production, use Longhorn, Rook/Ceph, or cloud provider CSI (e.g., DigitalOcean Block Storage). |

---

## 9. Quick-Start Scripts to Create

### `deploy/k8s/scripts/k3s-install.sh`
```bash
#!/bin/bash
# Install k3s for VeritasVPN development
curl -sfL https://get.k3s.io | sh -
mkdir -p ~/.kube
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown $(id -u):$(id -g) ~/.kube/config
echo "Waiting for k3s to be ready..."
kubectl wait --for=condition=Ready node --all --timeout=60s
kubectl create namespace veritas
echo "Done. Run: kubectl apply -k deploy/k8s/overlays/dev/"
```

### `deploy/k8s/scripts/push-images.sh`
```bash
#!/bin/bash
# Build and push all service images to local k3s registry
# (or tag for ghcr.io)
REGISTRY="${REGISTRY:-ghcr.io/veritasvpn}"
TAG="${TAG:-latest}"

services=("auth-svc" "wg-manager" "billing-svc" "veritas-agent")
dockerfiles=(
  "services/auth-svc/Dockerfile"
  "services/wg-manager/Dockerfile"
  "services/billing-svc/Dockerfile"
  "services/veritas-agent/Dockerfile"
)

for i in "${!services[@]}"; do
  IMAGE="${REGISTRY}/${services[$i]}:${TAG}"
  echo "Building ${IMAGE}..."
  docker build -t "${IMAGE}" -f "${dockerfiles[$i]}" .
  docker push "${IMAGE}"
done
```

---

## 10. Next Steps (Concrete Actions)

1. **Decide**: Local dev only first, or production-ready manifests from the start?
2. **Install k3s** on your dev machine.
3. **Create `deploy/k8s/`** directory structure as proposed in Section 6.
4. **Implement Phase 1** (PostgreSQL + Redis + NATS) and verify.
5. **Iterate** through Phases 2-7.
6. **Update CI/CD** to build and push Docker images on push.
7. **Update README** with K8s deployment instructions.
