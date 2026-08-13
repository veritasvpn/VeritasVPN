# VeritasVPN — Implementation Plan

> **Tagline:** *Privacy is truth.*
> **Approach:** WireGuard-only, open-source clients, radical transparency, solo-founder execution.
> **Repo:** Private — architecture, infra, and operational secrets reside here.

---

## Table of Contents

1. [Foundation & Strategy](#1-foundation--strategy)
2. [Technical Architecture](#2-technical-architecture)
3. [Backend Deep Dive](#3-backend-deep-dive)
4. [Client Architecture](#4-client-architecture)
5. [WireGuard Technical Spec](#5-wireguard-technical-spec)
6. [Security Architecture](#6-security-architecture)
7. [DevOps & Infrastructure](#7-devops--infrastructure)
8. [Implementation Phases](#8-implementation-phases)
9. [Financial Plan](#9-financial-plan)
10. [Roadmap & Timeline](#10-roadmap--timeline)

---

## 1. Foundation & Strategy

### 1.1 Mission

VeritasVPN exists to provide a **provably no-logs**, **open-source**, WireGuard-only VPN service.
Every component that touches user traffic is source-available. The server blueprint, client apps,
and provisioning system are fully auditable.

### 1.2 Legal Entity

| Aspect | Decision |
|--------|----------|
| Jurisdiction | Panama (no mandatory data retention laws, outside 5/9/14 Eyes) |
| Structure | Sociedad Anónima (Panamanian corporation) |
| Registered Agent | Local Panamanian legal firm |
| Banking | Panamanian corporate account + EMI (Wise Business / Mercury) |

### 1.3 Differentiation

| Feature | VeritasVPN | Mullvad | ProtonVPN |
|---------|-----------|---------|-----------|
| Protocol | WireGuard **only** | WG + OpenVPN | WG + OpenVPN + IKEv2 |
| Clients | Fully open-source | Open-source | Partially open-source |
| Servers | Fully open-source (Ansible/Terraform) | Closed | Closed |
| Anonymous accounts | Yes (no email) | Yes (account number) | Email required |
| RAM-only servers | Yes (diskless boot) | Partial | Partial |
| Crypto payments | Monero, BTC, ETH | BTC, BCH | BTC |

### 1.4 Target Audience

- Privacy-conscious consumers
- Journalists / activists in restricted regions
- Remote workers needing secure connections
- Torrent users (dedicated P2P servers)
- Developers who value auditable infrastructure

---

## 2. Technical Architecture

### 2.1 System Overview

```
                          ┌──────────────────────────────────────┐
                          │            Cloudflare                │
                          │    (DDoS Protection / CDN / DNS)     │
                          └──────┬───────────────────┬───────────┘
                                 │                   │
                    ┌────────────▼──────┐   ┌────────▼───────────┐
                    │   API Gateway     │   │   Landing Page     │
                    │   (Caddy/Nginx)   │   │   (Astro/Next.js)  │
                    │   TLS 1.3 only    │   │   Static + SSR     │
                    └────────┬──────────┘   └────────────────────┘
                             │
              ┌──────────────┼──────────────────┐
              │              │                  │
    ┌─────────▼─────┐  ┌─────▼──────┐  ┌───────▼────────┐
    │  Auth Service │  │ WG Manager │  │  Billing Svc   │
    │  (Go/gRPC)    │  │ (Go/gRPC)  │  │  (Go/REST)     │
    └───────┬───────┘  └─────┬──────┘  └───────┬────────┘
            │                │                 │
            └────────────────┼─────────────────┘
                             │
              ┌──────────────┼──────────────────┐
              │              │                  │
    ┌─────────▼─────┐  ┌─────▼──────┐  ┌───────▼────────┐
    │  PostgreSQL    │  │   Redis    │  │   NATS/JetStream│
    │  (Primary DB)  │  │  (Cache)   │  │  (Event Bus)   │
    └───────────────┘  └────────────┘  └────────────────┘
                                          │
                          ┌───────────────┼─────────────────┐
                          │               │                 │
                 ┌────────▼────┐  ┌───────▼──────┐  ┌──────▼──────┐
                 │ WG Server 1 │  │ WG Server 2  │  │ WG Server N │
                 │ (Amsterdam) │  │ (Tokyo)      │  │ (...)       │
                 │ Bare Metal  │  │ VPS          │  │             │
                 └─────────────┘  └──────────────┘  └─────────────┘
```

### 2.2 Technology Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Backend | Go 1.22+ | High concurrency, single binary deploys, `wireguard-go` ecosystem |
| Database | PostgreSQL 16 | ACID compliance, JSONB for flexible config, proven |
| Cache / Sessions | Redis 7 | Sub-millisecond latency, pub/sub, rate limiting |
| Event Bus | NATS + JetStream | Lightweight, at-least-once delivery, dead simple ops |
| API Protocol | gRPC (internal) + REST (public) | gRPC for service-to-service, REST for client SDK ease |
| API Gateway | Caddy | Auto-TLS, HTTP/2, simple Caddyfile config |
| VPN Protocol | WireGuard | Kernel module on servers, userspace in mobile apps |
| DNS | Unbound + blocklists | Self-hosted recursive resolver, no third-party logging |
| Monitoring | Prometheus + Grafana + Loki | Industry standard, open-source, no telemetry exfil |
| IaC | Terraform + Ansible | Declarative infra, reproducible server configs |
| CI/CD | GitHub Actions + ArgoCD | Private repo CI, GitOps deployments |
| Container Runtime | Podman or bare Docker (no k8s initially) | Solo founder — k8s is overkill until 10+ servers |
| Desktop Clients | Tauri 2 (Rust + React/TypeScript) | Small binary, secure, native performance |
| Mobile Clients | Flutter + `wireguard-go` via FFI | Single codebase for iOS/Android |

### 2.3 Network Diagram — Single WG Server

```
 ┌───────────────────────────────────────────────────────────┐
 │                    WG Server (e.g., ams1)                 │
 │                                                           │
 │  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐  │
 │  │ nftables    │  │ wg-quick /   │  │ Unbound DNS    │  │
 │  │ (NAT +      │  │ custom WG    │  │ (recursive     │  │
 │  │  killswitch │  │ controller)  │  │  resolver)     │  │
 │  │  rules)     │  │              │  │                │  │
 │  └──────┬──────┘  └──────┬───────┘  └───────┬────────┘  │
 │         │                │                   │            │
 │         ▼                ▼                   ▼            │
 │  ┌───────────────────────────────────────────────────┐   │
 │  │         Network Stack (kernel)                     │   │
 │  │  ┌──────────────┐  ┌──────────────┐               │   │
 │  │  │  wg0 (VPN)   │  │  eth0 (WAN)  │               │   │
 │  │  │  10.0.0.1/24 │  │  Public IP   │               │   │
 │  │  └──────────────┘  └──────────────┘               │   │
 │  │                                                    │   │
 │  │  IP Forwarding: ON                                 │   │
 │  │  NAT: wg0 → eth0 (masquerade)                      │   │
 │  │  Kill Switch: DROP all non-WG forwarded traffic    │   │
 │  └───────────────────────────────────────────────────┘   │
 │                                                           │
 │  ┌───────────────────────────────────────────────────┐   │
 │  │  Veritas Agent (Go daemon)                         │   │
 │  │  - Registers with WG Manager on boot               │   │
 │  │  - Fetches peer configs via gRPC stream            │   │
 │  │  - Reports bandwidth stats (aggregated, no PII)    │   │
 │  │  - Health checks + Prometheus metrics endpoint     │   │
 │  └───────────────────────────────────────────────────┘   │
 └───────────────────────────────────────────────────────────┘
```

---

## 3. Backend Deep Dive

### 3.1 Service: Auth (`auth-svc`)

```
┌─────────────────────────────────────────────────┐
│              POST /api/v1/auth/register          │
│  Request:  { device_id, public_key }             │
│  Response: { access_token, refresh_token,        │
│              account_id }                        │
│  Notes:    No email required. account_id is a    │
│            random 16-char alphanumeric string.   │
│            User MUST save this — it's the only   │
│            identifier for account recovery.      │
└─────────────────────────────────────────────────┘
```

**Data stored (PostgreSQL, `users` table):**
- `account_id` (UUID v4, PK)
- `hashed_device_id` (SHA-256, never stored raw)
- `hashed_public_key` (SHA-256)
- `created_at` (timestamp)
- `subscription_tier` (enum: `free`, `premium`)
- `subscription_expiry` (timestamp or NULL)
- `account_status` (enum: `active`, `suspended`, `deleted`)

**What is NOT stored:**
- IP addresses
- Email addresses
- Names
- Browsing history
- DNS queries
- Connection timestamps
- Bandwidth per user (only aggregate per server)

**Authentication flow:**
1. Client generates WireGuard keypair locally.
2. Client sends `device_id` (random UUID generated on first run) + WG public key.
3. Server creates account, returns `access_token` (JWT, short-lived 1h) + `refresh_token` (opaque, stored hashed in DB, 30d expiry).
4. All subsequent requests use `Authorization: Bearer <access_token>`.
5. Token refresh: `POST /api/v1/auth/refresh` with refresh token.

### 3.2 Service: WireGuard Manager (`wg-manager`)

This is the core service that provisions and manages WireGuard peers across all servers.

```
┌──────────────────────────────────────────────────────────┐
│                     wg-manager                           │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │  gRPC API                                        │    │
│  │  - CreatePeer(account_id, pubkey, region)        │    │
│  │  - DeletePeer(peer_id)                           │    │
│  │  - ListPeers(account_id)                         │    │
│  │  - GetServerStatus(server_id)                    │    │
│  │  - ListServers(region_filter)                    │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │  Server Registry (PostgreSQL)                    │    │
│  │  - servers: id, hostname, region, public_ip,    │    │
│  │    wg_port, status, capacity, load_factor        │    │
│  │  - peers: id, account_id, server_id, pubkey,     │    │
│  │    allowed_ips, preshared_key, created_at,       │    │
│  │    expires_at                                    │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │  Server Communicator (gRPC client)               │    │
│  │  - Pushes peer config to Veritas Agent on server │    │
│  │  - Receives stats from agent                     │    │
│  │  - Health checks (heartbeat every 10s)           │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │  Scheduler                                       │    │
│  │  - Assigns new peers to least-loaded server      │    │
│  │  - Rebalances on server join/leave               │    │
│  │  - Handles server failures (reassign peers)      │    │
│  └─────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
```

#### Peer Lifecycle (State Machine)

```
     ┌──────┐  CreatePeer()  ┌──────────┐  Agent ack  ┌────────┐
     │ NONE │ ───────────────>│ PENDING   │ ──────────>│ ACTIVE │
     └──────┘                └──────────┘             └───┬────┘
       ▲                         │                       │
       │                         │ (timeout)             │ ExpirePeer()
       │ DeletePeer()      ┌─────▼──────┐               │
       └────────────────────│  FAILED    │  ◄────────────┘
                            └────────────┘
```

#### Database Schema (SQL)

```sql
-- Servers table
CREATE TABLE servers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hostname    TEXT NOT NULL UNIQUE,
    region      TEXT NOT NULL,       -- e.g., 'eu-west', 'us-east', 'ap-northeast'
    city        TEXT NOT NULL,       -- e.g., 'Amsterdam'
    country     TEXT NOT NULL,       -- ISO 3166-1 alpha-2
    public_ip   INET NOT NULL,
    wg_port     INTEGER NOT NULL DEFAULT 51820,
    public_key  TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'offline',  -- offline, online, maintenance, decomissioned
    capacity    INTEGER NOT NULL DEFAULT 100,     -- max concurrent peers
    load_factor REAL NOT NULL DEFAULT 0.0,        -- current_peers / capacity
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Peers table
CREATE TABLE peers (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    server_id     UUID NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
    pubkey        TEXT NOT NULL,                 -- WireGuard public key (44-char base64)
    preshared_key TEXT,                          -- Optional PSK for post-quantum resistance
    allowed_ips   INET[] NOT NULL DEFAULT '{}',  -- Array of IPs assigned to this peer
    status        TEXT NOT NULL DEFAULT 'pending',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ,                   -- NULL for active subs, set on expiry
    UNIQUE(account_id, server_id)               -- One peer per account per server
);

CREATE INDEX idx_peers_account ON peers(account_id);
CREATE INDEX idx_peers_server ON peers(server_id);
CREATE INDEX idx_peers_pubkey ON peers(pubkey);
CREATE INDEX idx_servers_region ON servers(region);
```

#### IP Allocation Strategy

Each WireGuard server gets a `/24` subnet from the `10.0.0.0/8` range, managed in the database:

```sql
CREATE TABLE ip_pools (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    server_id   UUID NOT NULL UNIQUE REFERENCES servers(id),
    subnet      CIDR NOT NULL,              -- e.g., 10.1.0.0/24
    allocated   INTEGER NOT NULL DEFAULT 0,  -- number of IPs currently in use
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

IP allocation is done via a bitmap in Redis:
```
Key: ip_pool:{server_id}:bitmap  → 256-bit bitmap (SETBIT/GETBIT/BITPOS)
```

### 3.3 Service: Billing (`billing-svc`)

```
┌──────────────────────────────────────────────────────┐
│                    billing-svc                        │
│                                                       │
│  ┌──────────────────────────────────────────────┐    │
│  │  REST API                                     │    │
│  │  POST /api/v1/billing/subscribe              │    │
│  │  POST /api/v1/billing/cancel                 │    │
│  │  GET  /api/v1/billing/status                 │    │
│  │  POST /api/v1/billing/webhook/stripe         │    │
│  │  POST /api/v1/billing/webhook/btcpay         │    │
│  └──────────────────────────────────────────────┘    │
│                                                       │
│  ┌──────────────────────────────────────────────┐    │
│  │  Payment Providers                            │    │
│  │  - Stripe (CC, Apple Pay, Google Pay)         │    │
│  │  - BTCPay Server (self-hosted)                │    │
│  │    - Bitcoin (on-chain + Lightning)            │    │
│  │    - Monero (privacy-first option)             │    │
│  └──────────────────────────────────────────────┘    │
│                                                       │
│  ┌──────────────────────────────────────────────┐    │
│  │  Subscription Manager                         │    │
│  │  - Tracks active subscriptions                │    │
│  │  - Publishes events to NATS on:               │    │
│  │    - subscription.created                     │    │
│  │    - subscription.expired                     │    │
│  │    - subscription.renewed                     │    │
│  │  - auth-svc listens & updates account tier    │    │
│  └──────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────┘
```

### 3.4 Go Project Structure

```
services/
├── auth-svc/
│   ├── cmd/server/main.go
│   ├── internal/
│   │   ├── handler/         # gRPC/REST handlers
│   │   ├── service/         # Business logic
│   │   ├── repository/      # DB access (pgx)
│   │   ├── middleware/       # Auth, logging, rate limit
│   │   └── model/           # Domain types
│   ├── migrations/          # SQL migration files
│   ├── Dockerfile
│   └── go.mod
├── wg-manager/
│   ├── cmd/server/main.go
│   ├── internal/
│   │   ├── handler/
│   │   ├── service/
│   │   ├── repository/
│   │   ├── communicator/    # gRPC client to Veritas Agent
│   │   ├── scheduler/       # Peer-to-server assignment
│   │   └── model/
│   ├── migrations/
│   ├── Dockerfile
│   └── go.mod
├── billing-svc/
│   ├── cmd/server/main.go
│   ├── internal/
│   │   ├── handler/
│   │   ├── service/
│   │   ├── repository/
│   │   ├── provider/        # Stripe, BTCPay integrations
│   │   └── model/
│   ├── migrations/
│   ├── Dockerfile
│   └── go.mod
├── veritas-agent/
│   ├── cmd/agent/main.go
│   ├── internal/
│   │   ├── wireguard/       # wg-quick wrapper / netlink
│   │   ├── peer/            # Peer config management
│   │   ├── firewall/        # nftables rules management
│   │   └── metrics/         # Prometheus exporter
│   ├── Dockerfile
│   └── go.mod
├── api/
│   ├── proto/               # Shared protobuf definitions
│   │   ├── auth/v1/auth.proto
│   │   ├── wg/v1/wg.proto
│   │   └── agent/v1/agent.proto
│   └── gen/                 # Generated Go code
├── lib/
│   ├── config/              # Shared config loading
│   ├── logging/             # Structured logging (zerolog/slog)
│   ├── crypto/              # Key generation, hashing
│   └── jwt/                 # JWT creation/validation
└── docker-compose.yml       # Local dev environment
```

### 3.5 gRPC Proto Example

```protobuf
// api/proto/wg/v1/wg.proto
syntax = "proto3";
package wg.v1;
option go_package = "github.com/veritasvpn/api/gen/wg/v1;wgv1";

service WireGuardService {
  rpc CreatePeer(CreatePeerRequest) returns (CreatePeerResponse);
  rpc DeletePeer(DeletePeerRequest) returns (DeletePeerResponse);
  rpc ListPeers(ListPeersRequest) returns (ListPeersResponse);
  rpc ListServers(ListServersRequest) returns (ListServersResponse);
  rpc GetServerConfig(GetServerConfigRequest) returns (GetServerConfigResponse);
}

message CreatePeerRequest {
  string account_id = 1;
  string public_key = 2;
  string preferred_region = 3;  // optional, empty = auto-assign
}

message CreatePeerResponse {
  string peer_id = 1;
  string server_hostname = 2;
  string server_public_key = 3;
  string server_endpoint = 4;   // ip:port
  string assigned_ip = 5;       // e.g., 10.1.0.42/32
  string dns_server = 6;        // e.g., 10.1.0.1
  repeated string allowed_ips = 7;
}

message ListServersRequest {
  optional string region = 1;
}

message ListServersResponse {
  repeated Server servers = 1;
}

message Server {
  string id = 1;
  string hostname = 2;
  string region = 3;
  string city = 4;
  string country = 5;
  double load_factor = 6;
  string status = 7;
}
```

### 3.6 Key Libraries & Dependencies

```go
// go.mod (representative)
module github.com/veritasvpn/wg-manager

go 1.22

require (
    github.com/jackc/pgx/v5          // PostgreSQL driver
    github.com/redis/go-redis/v9     // Redis client
    github.com/nats-io/nats.go       // NATS client
    github.com/grpc-ecosystem/grpc-gateway/v2  // REST-to-gRPC gateway
    github.com/bufbuild/buf          // Proto linting & generation
    go.uber.org/zap                  // Structured logging
    github.com/prometheus/client_golang // Metrics
    github.com/go-playground/validator  // Input validation
    golang.zx2c4.com/wireguard/wgctrl  // WireGuard netlink control
    golang.zx2c4.com/wireguard/conn     // WireGuard connection
)
```

---

## 4. Client Architecture

### 4.1 Desktop: Tauri 2 + React

```
┌─────────────────────────────────────────────────────────┐
│                  Desktop App (Tauri)                     │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │  React Frontend (TypeScript)                     │    │
│  │  ┌───────────┐ ┌──────────┐ ┌────────────────┐  │    │
│  │  │ Dashboard │ │Settings  │ │ Server Picker  │  │    │
│  │  │ - Status   │ │ - Proto  │ │ - List w/ ping │  │    │
│  │  │ - Connect  │ │ - Kill   │ │ - Favorites    │  │    │
│  │  │ - IP info  │ │   Switch │ │ - Auto-select  │  │    │
│  │  │ - Bandwidth│ │ - Split  │ │ - Search/filter│  │    │
│  │  │            │ │   Tunnel │ │                │  │    │
│  │  └───────────┘ └──────────┘ └────────────────┘  │    │
│  │  State: Zustand / Jotai                         │    │
│  │  UI: TailwindCSS + shadcn/ui                    │    │
│  └─────────────────────────────────────────────────┘    │
│                          │                               │
│  ┌─────────────────────────────────────────────────┐    │
│  │  Tauri Commands (Rust)                          │    │
│  │  ┌───────────────┐ ┌──────────────┐             │    │
│  │  │ wg_connect()  │ │ wg_disconnect│             │    │
│  │  │ wg_status()   │ │ get_servers()│             │    │
│  │  │ set_killswitch│ │ set_dns()    │             │    │
│  │  │ set_split_tunnel│ │ login()    │             │    │
│  │  └───────────────┘ └──────────────┘             │    │
│  │                                                  │    │
│  │  ┌──────────────────────────────────────────┐   │    │
│  │  │ WireGuard Controller (Rust)               │   │    │
│  │  │ - Uses `wireguard-rs` or system `wg` CLI  │   │    │
│  │  │ - Interface creation/deletion             │   │    │
│  │  │ - Config generation from API response     │   │    │
│  │  │ - Persistent tunnel state                 │   │    │
│  │  └──────────────────────────────────────────┘   │    │
│  │                                                  │    │
│  │  ┌──────────────────────────────────────────┐   │    │
│  │  │ Kill Switch Manager (Rust)                │   │    │
│  │  │ - Platform-specific firewall rules        │   │    │
│  │  │   Linux: nftables                         │   │    │
│  │  │   macOS: pfctl (Packet Filter)             │   │    │
│  │  │   Windows: WFP (Windows Filtering Platform)│   │    │
│  │  └──────────────────────────────────────────┘   │    │
│  │                                                  │    │
│  │  ┌──────────────────────────────────────────┐   │    │
│  │  │ API Client (Rust, reqwest)                │   │    │
│  │  │ - Auth token management                   │   │    │
│  │  │ - Server list polling                     │   │    │
│  │  │ - Peer config fetching                    │   │    │
│  │  └──────────────────────────────────────────┘   │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

#### Kill Switch Implementation per Platform

**Linux (nftables):**
```nftables
table inet veritas-killswitch {
    chain prerouting {
        type filter hook prerouting priority filter; policy accept;
        # Allow outbound WireGuard traffic
        ip saddr {wg_client_ip} udp dport 51820 accept
        # Allow LAN traffic (optional)
        ip daddr 192.168.0.0/16 accept
        ip daddr 10.0.0.0/8 accept
        ip daddr 172.16.0.0/12 accept
        # Block all other traffic when kill switch is on
        ip saddr {wg_client_ip} drop
    }
}
```

**macOS (pfctl):**
```
# /etc/pf.conf (anchor added dynamically)
anchor "veritas-killswitch"
load anchor "veritas-killswitch" from "/tmp/veritas-pf.rules"

# veritas-pf.rules:
block drop out proto udp from any to any port != 51820
pass out proto udp from any to {server_ip} port 51820
```

**Windows (WFP):**
Use `windows-rs` crate to interact with Windows Filtering Platform API. Create filter rules that block all non-WireGuard traffic when the kill switch is active.

### 4.2 Mobile: Flutter + `wireguard-go`

```
┌─────────────────────────────────────────────────────────┐
│                  Mobile App (Flutter)                    │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │  Flutter Dart UI                                │    │
│  │  ┌───────────┐ ┌──────────┐ ┌────────────────┐  │    │
│  │  │ Dashboard │ │Server List│ │   Settings     │  │    │
│  │  │ - Connect │ │ - Ping    │ │ - Protocol     │  │    │
│  │  │ - Status  │ │ - Search  │ │ - Kill Switch  │  │    │
│  │  │ - IP      │ │ - Favs    │ │ - Auto-connect │  │    │
│  │  └───────────┘ └──────────┘ └────────────────┘  │    │
│  │  State: Riverpod / Bloc                         │    │
│  └─────────────────────────────────────────────────┘    │
│                          │                               │
│  ┌─────────────────────────────────────────────────┐    │
│  │  Method Channel (Dart ↔ Native)                 │    │
│  │  - startVpn(config)                             │    │
│  │  - stopVpn()                                    │    │
│  │  - getVpnStatus()                               │    │
│  │  - getConnectionStats()                         │    │
│  └─────────────────────────────────────────────────┘    │
│                          │                               │
│  ┌──────────────────┐  ┌────────────────────────────┐   │
│  │  Android (Kotlin) │  │  iOS (Swift)               │   │
│  │                   │  │                            │   │
│  │ wireguard-android │  │ WireGuardKit (Swift)       │   │
│  │ (tunnel library)  │  │ - NetworkExtension         │   │
│  │ - VpnService      │  │ - NEPacketTunnelProvider   │   │
│  │ - GoBackend       │  │ - wireguard-go backend     │   │
│  └──────────────────┘  └────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

#### Mobile WireGuard Integration

**Android:**
- Use `wireguard-android` library (wraps `wireguard-go` in a VpnService).
- The app passes WireGuard config (as a conf string) to the tunnel service.
- Native `VpnService.Builder` sets up the tunnel interface.

**iOS:**
- Use `WireGuardKit` (Swift package wrapping `wireguard-go` via the WireGuard Apple extensions).
- Create a `NEPacketTunnelProvider` that starts/stops the tunnel.
- The main app communicates with the tunnel extension via `NETunnelProviderManager`.

### 4.3 CLI Client

```go
// CLI tool for headless systems (Linux servers, routers)
// Commands:
//   veritas login --account-id <id>
//   veritas connect [--region us-east] [--server ams1]
//   veritas disconnect
//   veritas status
//   veritas list-servers
//   veritas killswitch on|off

// WireGuard config is written to /etc/wireguard/veritas.conf
// wg-quick up/down manages the interface
```

---

## 5. WireGuard Technical Spec

### 5.1 WireGuard Config Template (per client)

```ini
[Interface]
PrivateKey = <client_private_key>        # Generated locally, NEVER sent to server
Address = 10.1.0.42/32                   # Assigned by wg-manager
DNS = 10.1.0.1                           # DNS server on the WG server
MTU = 1420                               # Optimal for most connections

[Peer]
PublicKey = <server_public_key>          # Fetched from server list API
PresharedKey = <optional_psk>            # For post-quantum resistance
AllowedIPs = 0.0.0.0/0, ::/0            # Full tunnel (or split tunnel IPs)
Endpoint = 1.2.3.4:51820                 # Server public IP + port
PersistentKeepalive = 25                 # Keep NAT mappings alive
```

### 5.2 WireGuard Server Config (per server)

```ini
# /etc/wireguard/wg0.conf
[Interface]
PrivateKey = <server_private_key>       # Generated during server provisioning
Address = 10.1.0.1/24                   # Server's internal VPN IP
ListenPort = 51820                      # Standard WG port
MTU = 1420
PostUp = nft add rule ip nat POSTROUTING oifname eth0 masquerade
PostUp = nft add rule ip filter FORWARD iifname wg0 jump veritas-forward
PostDown = nft delete rule ip nat POSTROUTING oifname eth0 masquerade
PostDown = nft delete rule ip filter FORWARD iifname wg0 jump veritas-forward

# Peers are dynamically managed by veritas-agent via netlink (wg set),
# NOT written to this file. This avoids the need to restart wg-quick.
# The agent uses the `wgctrl` Go library to add/remove peers in real-time.

[Peer]  # Example peer — managed dynamically
PublicKey = <client_public_key>
AllowedIPs = 10.1.0.42/32
```

### 5.3 Veritas Agent — WireGuard Management (Go)

```go
// internal/wireguard/manager.go
package wireguard

import (
    "golang.zx2c4.com/wireguard/wgctrl"
    "golang.zx2c4.com/wireguard/wgctrl/wgtypes"
)

type Manager struct {
    client  *wgctrl.Client
    iface   string // "wg0"
}

func NewManager(iface string) (*Manager, error) {
    client, err := wgctrl.New()
    if err != nil {
        return nil, fmt.Errorf("failed to create wgctrl client: %w", err)
    }
    return &Manager{client: client, iface: iface}, nil
}

func (m *Manager) AddPeer(pubkey string, allowedIPs []net.IPNet) error {
    key, err := wgtypes.ParseKey(pubkey)
    if err != nil {
        return fmt.Errorf("invalid public key: %w", err)
    }

    peer := wgtypes.PeerConfig{
        PublicKey:         key,
        ReplaceAllowedIPs: true,
        AllowedIPs:        allowedIPs,
        PersistentKeepaliveInterval: ptr(25 * time.Second),
    }

    cfg := wgtypes.Config{
        Peers: []wgtypes.PeerConfig{peer},
    }

    return m.client.ConfigureDevice(m.iface, cfg)
}

func (m *Manager) RemovePeer(pubkey string) error {
    key, err := wgtypes.ParseKey(pubkey)
    if err != nil {
        return fmt.Errorf("invalid public key: %w", err)
    }

    cfg := wgtypes.Config{
        Peers: []wgtypes.PeerConfig{
            {
                PublicKey: key,
                Remove:    true,
            },
        },
    }

    return m.client.ConfigureDevice(m.iface, cfg)
}

func (m *Manager) ListPeers() ([]wgtypes.Peer, error) {
    device, err := m.client.Device(m.iface)
    if err != nil {
        return nil, err
    }
    return device.Peers, nil
}
```

### 5.4 Key Rotation Strategy

1. **Server keys**: Rotated on every full server reprovision (Ansible re-run). New key pair generated, peers are re-provisioned with the new public key via API.
2. **Client keys**: Users can regenerate from settings. Old peer deleted, new one created. Both keys active briefly during transition (60s overlap to avoid connection drops).
3. **PSK (Post-Quantum)**: Optional. Generated server-side if user opts in. Shared via encrypted channel during peer creation.

### 5.5 DNS Configuration

Each WG server runs **Unbound** as a recursive DNS resolver:

```yaml
# unbound.conf (on each WG server)
server:
    interface: 10.1.0.1          # Listen on VPN interface only
    access-control: 10.1.0.0/24 allow
    access-control: 127.0.0.0/8 allow
    port: 53
    do-ip4: yes
    do-ip6: yes
    do-udp: yes
    do-tcp: yes
    hide-identity: yes
    hide-version: yes
    qname-minimisation: yes       # Privacy: minimal info to upstream
    prefetch: yes
    cache-min-ttl: 3600
    cache-max-ttl: 86400
    rrset-cache-size: 100m
    msg-cache-size: 50m
    num-threads: 2
    root-hints: "/etc/unbound/root.hints"
```

**Ad/tracker blocking** is applied via blocklists:
- `oisd-full` or `StevenBlack/hosts` list
- Updated daily via cron job (`/etc/cron.daily/unbound-blocklist-update`)
- Converted to Unbound `local-zone` format:
  ```
  local-zone: "doubleclick.net" always_nxdomain
  local-zone: "google-analytics.com" always_nxdomain
  ```

Users can toggle ad blocking from client settings (changes DNS server pushed to client: ad-blocking DNS vs. clean DNS on the same server, different port).

### 5.6 Bandwidth Accounting (Privacy-Preserving)

We track **aggregate** bandwidth per server, NOT per user:

```go
// On each WG server, veritas-agent polls wg interface stats every 60s.
// Reports to wg-manager:
//   { server_id, timestamp, total_rx_bytes, total_tx_bytes }
// Wg-manager stores in PostgreSQL:
//   server_metrics (server_id, timestamp, rx_bytes, tx_bytes, peer_count)
// No per-peer/per-user metrics are ever stored or transmitted.
```

---

## 6. Security Architecture

### 6.1 No-Logs: Technical Proof

**What we NEVER store:**
- Source IP addresses (connection logs)
- DNS query logs (Unbound logging DISABLED: `verbosity: 0`, no query log file)
- Timestamps of connections
- Bandwidth consumed per user
- Websites visited / traffic content
- MAC addresses, device identifiers (beyond `hashed_device_id`

**What we DO store (necessary for operation):**
- `hashed_device_id` (SHA-256, for session management)
- `account_id` (UUID v4, random)
- `subscription_tier` and `expiry`
- `hashed_public_key` (for peer deduplication)
- Server-level aggregate bandwidth (for capacity planning)
- Server-level peer count (for load balancing)

**Data retention policy:**
- Account data: deleted immediately upon account deletion request.
- Expired subscriptions: peer data purged after 30 days of expiry.
- Aggregate server metrics: retained for 90 days, then aggregated to daily rolls.

### 6.2 Server Hardening

```bash
# Ansible hardening tasks (playbook excerpt)

- name: Disable unnecessary services
  service: name={{ item }} state=stopped enabled=no
  loop:
    - avahi-daemon
    - cups
    - bluetooth
    - snapd

- name: Configure kernel parameters
  sysctl:
    name: "{{ item.key }}"
    value: "{{ item.value }}"
    sysctl_set: yes
    state: present
    reload: yes
  loop:
    - { key: 'net.ipv4.ip_forward', value: '1' }
    - { key: 'net.ipv4.conf.all.rp_filter', value: '1' }
    - { key: 'net.ipv4.conf.default.rp_filter', value: '1' }
    - { key: 'net.ipv4.conf.all.accept_redirects', value: '0' }
    - { key: 'net.ipv6.conf.all.accept_redirects', value: '0' }
    - { key: 'net.ipv4.conf.all.send_redirects', value: '0' }
    - { key: 'kernel.kptr_restrict', value: '2' }
    - { key: 'kernel.dmesg_restrict', value: '1' }
    - { key: 'net.core.bpf_jit_harden', value: '2' }

- name: Install and configure nftables firewall
  template:
    src: nftables.conf.j2
    dest: /etc/nftables.conf
  notify: reload nftables

- name: Enable automatic security updates (unattended-upgrades)
  apt:
    name: unattended-upgrades
    state: present
  when: ansible_os_family == "Debian"

- name: SSH hardening
  lineinfile:
    path: /etc/ssh/sshd_config
    regexp: "{{ item.regexp }}"
    line: "{{ item.line }}"
  loop:
    - { regexp: '^PermitRootLogin', line: 'PermitRootLogin no' }
    - { regexp: '^PasswordAuthentication', line: 'PasswordAuthentication no' }
    - { regexp: '^X11Forwarding', line: 'X11Forwarding no' }
    - { regexp: '^MaxAuthTries', line: 'MaxAuthTries 3' }
  notify: restart sshd

- name: WireGuard only — remove OpenVPN/IPsec
  apt:
    name: "{{ item }}"
    state: absent
    purge: yes
  loop:
    - openvpn
    - strongswan
    - libreswan
  ignore_errors: yes
```

### 6.3 Diskless / RAM-Only Server

**Ideal state:** Servers boot from network (PXE), load the OS entirely into RAM, and run without persistent storage.

```
┌─────────────────────────────────────────────────────┐
│                Boot Process                          │
│                                                      │
│  1. Server PXE boots                                 │
│  2. Downloads kernel + initramfs via TFTP             │
│  3. Initramfs fetches OS image via HTTPS              │
│  4. Entire rootfs loaded into tmpfs (RAM)             │
│  5. Ansible / cloud-init configures the server       │
│  6. Veritas Agent starts, registers with wg-manager   │
│                                                      │
│  No disks mounted. No persistent state.               │
│  On reboot → fresh image, zero residual data.         │
└─────────────────────────────────────────────────────┘
```

**Pragmatic alternative (Phase 1):** Use VPS with an encrypted swap partition and `tmpfs` for `/tmp`, `/var/log`, and WireGuard runtime state. Full diskless can be deferred to Phase 6.

### 6.4 Threat Model

| Threat | Mitigation |
|--------|-----------|
| Server seizure | RAM-only, no logs on disk. Nothing to extract. |
| Subpoena / gag order | No data retained = nothing to hand over. Warrant canary page. |
| Traffic correlation | No connection logs, no timestamps. Impossible to correlate user ↔ activity. |
| MITM on WG traffic | WireGuard's Noise protocol prevents this. Perfect forward secrecy. |
| DDoS on servers | Cloudflare Magic Transit / DDoS scrubbing on origin IPs. |
| Credential stuffing | Rate limiting (Redis), no password-based auth (key-based only). |
| Malicious insider | Code review required. All infra changes via GitOps (pull request). No direct SSH access except for emergency (break-glass account, audited). |

### 6.5 Warrant Canary

A "warrant canary" page at `https://veritasvpn.com/canary.txt`:
- Updated monthly with a signed statement: "As of [date], VeritasVPN has received 0 national security letters, 0 gag orders, and 0 warrants."
- If not updated, assume it has been compromised.
- PGP-signed by the founder's key for verifiability.

### 6.6 External Security Audits

- **Year 1**: Self-audit + publish server configs and client source.
- **Year 2**: Third-party audit by Cure53, Trail of Bits, or similar.
- **Ongoing**: Bug bounty program (HackerOne or self-hosted via `opencollective`).

---

## 7. DevOps & Infrastructure

### 7.1 Infrastructure as Code

**Terraform** provisions compute resources:

```hcl
# terraform/main.tf (excerpt)
terraform {
  required_providers {
    hetzner = {
      source = "hetznercloud/hcloud"
    }
    vultr = {
      source = "vultr/vultr"
    }
    digitalocean = {
      source = "digitalocean/digitalocean"
    }
  }

  backend "s3" {
    # Backend for state — could be Cloudflare R2 or a simple VPS
    bucket = "veritas-tf-state"
    key    = "production/terraform.tfstate"
    region = "auto"
  }
}

# Multi-cloud, multi-region server fleet
# Priority providers due to cost + privacy:
# 1. Hetzner (EU, cheap, good privacy laws)
# 2. Vultr (global, bare metal options)
# 3. BuyVM / FranTech (privacy-friendly, crypto payments)
# AVOID: AWS, GCP, Azure (US jurisdiction, expensive)

module "server_ams1" {
  source = "./modules/wg-server"

  providers = {
    cloud = hetzner
  }

  hostname    = "ams1.veritasvpn.com"
  datacenter  = "fsn1-dc14"          # Falkenstein, DE (Hetzner)
  server_type = "cpx21"              # 3 vCPU, 4 GB RAM
  image       = "debian-12"

  wg_subnet      = "10.1.0.0/24"
  wg_port        = 51820
  ansible_playbook = "../ansible/wg-server.yml"
}
```

**Ansible** configures the server post-provisioning:

```yaml
# ansible/wg-server.yml (excerpt)
- name: Configure WireGuard VPN server
  hosts: vpn_servers
  become: yes
  vars:
    wg_iface: wg0
    wg_port: 51820
    wg_subnet: "{{ lookup('env', 'WG_SUBNET') }}"
    veritas_agent_version: "v0.1.0"

  tasks:
    - name: Install WireGuard
      apt:
        name: wireguard
        state: present

    - name: Generate WireGuard server keys
      shell: |
        umask 077
        wg genkey | tee /etc/wireguard/privatekey | wg pubkey > /etc/wireguard/publickey
      args:
        creates: /etc/wireguard/privatekey
      register: wg_keys

    - name: Create WireGuard config
      template:
        src: wg0.conf.j2
        dest: /etc/wireguard/wg0.conf
        mode: '0600'
      notify: restart wireguard

    - name: Enable WireGuard on boot
      service:
        name: wg-quick@wg0
        enabled: yes
        state: started

    - name: Install and configure Unbound DNS
      include_role:
        name: unbound

    - name: Deploy Veritas Agent
      copy:
        src: "binaries/veritas-agent-{{ veritas_agent_version }}-linux-amd64"
        dest: /usr/local/bin/veritas-agent
        mode: '0755'

    - name: Create Veritas Agent systemd service
      template:
        src: veritas-agent.service.j2
        dest: /etc/systemd/system/veritas-agent.service
      notify: restart veritas-agent

    - name: Enable Veritas Agent
      service:
        name: veritas-agent
        enabled: yes
        state: started

    - name: Register server with WG Manager API
      uri:
        url: "https://api.veritasvpn.com/api/v1/servers/register"
        method: POST
        body_format: json
        body:
          hostname: "{{ inventory_hostname }}"
          public_key: "{{ lookup('file', '/etc/wireguard/publickey') }}"
          public_ip: "{{ ansible_default_ipv4.address }}"
          wg_port: "{{ wg_port }}"
          region: "{{ server_region }}"
          city: "{{ server_city }}"
          country: "{{ server_country }}"
        headers:
          Authorization: "Bearer {{ admin_api_token }}"
```

### 7.2 CI/CD Pipeline

```
┌──────────────────────────────────────────────────────┐
│                  GitHub Actions                       │
│                                                       │
│  ┌─────────────────────────────────────────────┐     │
│  │  On push to main:                            │     │
│  │  ┌──────────────────────────────────────┐   │     │
│  │  │ 1. Lint (golangci-lint, eslint)      │   │     │
│  │  │ 2. Unit tests (go test, jest)        │   │     │
│  │  │ 3. Build Docker images               │   │     │
│  │  │ 4. Scan images (Trivy)               │   │     │
│  │  │ 5. Push to private container registry │   │     │
│  │  │ 6. Deploy staging environment         │   │     │
│  │  │ 7. Integration tests                  │   │     │
│  │  │ 8. Manual approval → deploy prod      │   │     │
│  │  └──────────────────────────────────────┘   │     │
│  └─────────────────────────────────────────────┘     │
│                                                       │
│  ┌─────────────────────────────────────────────┐     │
│  │  On push to infra/ directory:                │     │
│  │  1. Terraform plan                           │     │
│  │  2. Manual approval                          │     │
│  │  3. Terraform apply                          │     │
│  │  4. Ansible run on new servers               │     │
│  └─────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────┘
```

### 7.3 Monitoring Stack

```yaml
# docker-compose.monitoring.yml
services:
  prometheus:
    image: prom/prometheus:v2.52.0
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus_data:/prometheus
    command:
      - '--storage.tsdb.retention.time=30d'
      - '--storage.tsdb.path=/prometheus'

  grafana:
    image: grafana/grafana:11.0.0
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_PASSWORD}
      - GF_AUTH_ANONYMOUS_ENABLED=false
      - GF_USERS_ALLOW_SIGN_UP=false
    volumes:
      - ./grafana/dashboards:/etc/grafana/provisioning/dashboards
      - ./grafana/datasources:/etc/grafana/provisioning/datasources
      - grafana_data:/var/lib/grafana

  loki:
    image: grafana/loki:3.0.0
    command: -config.file=/etc/loki/local-config.yaml

  node_exporter:   # Per server
    image: prom/node-exporter:v1.8.0
    network_mode: host
    command:
      - '--collector.ntp'
      - '--collector.processes'
```

**Key Prometheus alerts:**
- Server offline for > 3 minutes
- Bandwidth saturation > 80% of capacity
- Peer count approaching server capacity limit
- Certificate expiry < 30 days
- API error rate > 5%

### 7.4 Docker Compose — Local Dev

```yaml
# docker-compose.yml (root of repo, for local dev)
version: '3.9'

services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: veritas
      POSTGRES_USER: veritas
      POSTGRES_PASSWORD: veritas_dev
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./services/auth-svc/migrations:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD", "pg_isready", "-U", "veritas"]
      interval: 5s

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --appendonly no --maxmemory-policy allkeys-lru
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s

  nats:
    image: nats:2.10-alpine
    ports:
      - "4222:4222"
      - "8222:8222"   # monitoring
    command: -js -m 8222

  auth-svc:
    build: ./services/auth-svc
    ports:
      - "8081:8080"
    environment:
      DATABASE_URL: postgres://veritas:veritas_dev@postgres:5432/veritas
      REDIS_URL: redis://redis:6379
      JWT_SECRET: dev_secret_change_in_prod
      LOG_LEVEL: debug
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy

  wg-manager:
    build: ./services/wg-manager
    ports:
      - "8082:8080"
    environment:
      DATABASE_URL: postgres://veritas:veritas_dev@postgres:5432/veritas
      REDIS_URL: redis://redis:6379
      NATS_URL: nats://nats:4222
      LOG_LEVEL: debug
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy

  billing-svc:
    build: ./services/billing-svc
    ports:
      - "8083:8080"
    environment:
      DATABASE_URL: postgres://veritas:veritas_dev@postgres:5432/veritas
      NATS_URL: nats://nats:4222
      STRIPE_SECRET_KEY: ${STRIPE_SECRET_KEY:-sk_test_placeholder}
      LOG_LEVEL: debug
    depends_on:
      postgres:
        condition: service_healthy

volumes:
  pgdata:
```

---

## 8. Implementation Phases

### Phase 0: Foundation (Months 1–2)

**Goal:** Legal structure, brand, architecture finalized. Dev environment running.

| Week | Tasks |
|------|-------|
| 1-2 | Register Panamanian corporation. Open corporate bank account. |
| 2-3 | Register domain `veritasvpn.com`. Set up Cloudflare DNS. |
| 3-4 | Write Privacy Policy, Terms of Service, Warrant Canary template. |
| 3-4 | Design logo, brand identity, color palette. |
| 4-5 | Build landing page (Astro/Next.js + Tailwind, deployed to Cloudflare Pages). |
| 5-6 | Set up private GitHub repo with branch protection rules. |
| 6-7 | Set up Docker Compose dev environment (PostgreSQL, Redis, NATS). |
| 7-8 | Write architecture decision records (ADRs) for key tech choices. |
| 8 | Define gRPC proto contracts. Generate Go stubs. Set up Buf for proto management. |

**Deliverables:**
- [ ] Registered company
- [ ] Bank account operational
- [ ] `veritasvpn.com` live with landing page
- [ ] Private repo with CI, Docker Compose dev env
- [ ] Proto contracts defined and stubbed

### Phase 1: Core Backend (Months 3–4)

**Goal:** Auth, WireGuard manager, and billing services built and tested.

| Week | Tasks |
|------|-------|
| 1-2 | Implement `auth-svc`: registration (no-email), token issuance, refresh, account management. |
| 2-3 | Implement `auth-svc`: middleware, rate limiting, Redis session store. |
| 3-4 | Implement `wg-manager`: server registry, peer CRUD, server status polling. |
| 4-5 | Implement `wg-manager`: scheduler (peer-to-server assignment), IP allocation via Redis bitmap. |
| 5-6 | Implement `veritas-agent`: WireGuard interface management via `wgctrl`, gRPC server. |
| 6-7 | Implement `veritas-agent`: nftables rules, Prometheus metrics, health checks. |
| 7-8 | Integration tests: full flow (register → create peer → agent receives → tunnel up). |
| 8 | Implement `billing-svc`: Stripe integration, subscription lifecycle, webhooks. |

**Deliverables:**
- [ ] Auth service with token auth
- [ ] WG Manager with peer lifecycle management
- [ ] Veritas Agent running on WG servers
- [ ] Billing service with Stripe
- [ ] All services with unit + integration tests (>80% coverage)

### Phase 2: Server Fleet (Months 5–6)

**Goal:** Deploy production WG servers, automate provisioning.

| Week | Tasks |
|------|-------|
| 1-2 | Write Terraform modules for each cloud provider (Hetzner, Vultr, BuyVM). |
| 2-3 | Write Ansible playbook for WG server provisioning (os hardening, WireGuard, Unbound, Veritas Agent). |
| 3-4 | Provision 5 initial servers: Amsterdam (NL), Ashburn (US), Singapore (SG), Frankfurt (DE), Toronto (CA). |
| 4-5 | Set up monitoring stack (Prometheus, Grafana, Loki) on a monitoring server. |
| 5-6 | Set up NATS cluster for production event bus. |
| 6-7 | Deploy all backend services to production (bare VPS or lightweight k8s). |
| 7-8 | API gateway (Caddy) with TLS, rate limiting, DDoS protection via Cloudflare. |

**Deliverables:**
- [ ] 5 production WG servers in key regions
- [ ] Fully automated provisioning (Terraform + Ansible)
- [ ] Monitoring and alerting operational
- [ ] Backend API live at `api.veritasvpn.com`

### Phase 3: CLI Client (Month 7)

**Goal:** Working CLI client for Linux/macOS. Early testers can connect.

| Week | Tasks |
|------|-------|
| 1-2 | Implement `veritas` CLI in Go: login, token storage (keyring), server list, connect. |
| 2-3 | Implement disconnect, status, region/server filtering, kill switch toggle. |
| 3-4 | Implement auto-connect on boot, connection stats. Cross-compile for Linux/macOS/Windows. |

**Deliverables:**
- [ ] CLI client with full feature set
- [ ] Cross-platform builds in CI
- [ ] Can connect to production servers

### Phase 4: Desktop Client (Months 8–10)

**Goal:** Full-featured desktop app for Windows, macOS, Linux.

| Week | Tasks |
|------|-------|
| 1-3 | Set up Tauri 2 project. Rust backend: WireGuard controller, API client, kill switch. |
| 3-5 | React frontend: Dashboard, server list with latency pings, connect/disconnect flow. |
| 5-7 | Settings page: protocol options, kill switch, split tunneling, auto-connect, DNS. |
| 7-9 | Platform-specific kill switch (nftables/pfctl/WFP). System tray integration. |
| 9-10 | Auto-updater. Packaging (.deb, .rpm, .dmg, .msi). Code signing. |

**Deliverables:**
- [ ] Windows desktop app (MSI installer)
- [ ] macOS desktop app (DMG, notarized)
- [ ] Linux desktop app (.deb, .rpm, AppImage)
- [ ] All features: connect, kill switch, split tunnel, auto-connect

### Phase 5: Mobile Clients (Months 10–12)

**Goal:** iOS and Android apps.

| Week | Tasks |
|------|-------|
| 1-3 | Set up Flutter project. Implement API client, state management, UI shell. |
| 3-5 | Dashboard and server list UI. Connect/disconnect flow via platform channel. |
| 5-7 | Android: Integrate `wireguard-android` tunnel library. VpnService implementation. |
| 7-9 | iOS: Integrate WireGuardKit. NEPacketTunnelProvider implementation. |
| 9-11 | Settings: kill switch, auto-connect, on-demand VPN (iOS). |
| 11-12 | App Store submission, Google Play submission. Beta testing (TestFlight, Internal Testing). |

**Deliverables:**
- [ ] iOS app published to App Store
- [ ] Android app published to Google Play
- [ ] All features parity with desktop

### Phase 6: Billing & Payments (Month 12–13)

**Goal:** Monetization live. Users can subscribe and pay.

| Week | Tasks |
|------|-------|
| 1-3 | Self-host BTCPay Server for crypto payments (Bitcoin, Monero). |
| 3-5 | Integrate BTCPay webhooks into billing-svc. |
| 5-6 | Build subscription management UI in client apps (upgrade/downgrade, cancel). |
| 6-7 | Implement freemium tier (2GB/mo, 5 server locations). |
| 7-8 | Pricing page on website. Payment flow end-to-end test. |

**Deliverables:**
- [ ] Stripe + crypto payments live
- [ ] Freemium + Premium tiers
- [ ] Subscription management in all clients

### Phase 7: Scale & Harden (Months 13–18)

**Goal:** Expand server fleet, security audit, multi-hop, ad blocking.

| Week | Tasks |
|------|-------|
| 1-3 | Expand to 20+ server locations. Optimize region coverage. |
| 3-6 | Implement multi-hop VPN (client → server A → server B → internet). |
| 6-8 | Ad/tracker blocking DNS (Unbound + blocklists, toggle per client). |
| 8-10 | External security audit (Cure53 or similar). Address findings. |
| 10-12 | Bug bounty program launch. |
| 12-14 | Implement stealth protocol / obfuscation for restrictive regions (WG over TCP/WebSocket, shadowsocks-like wrapper). |
| 14-16 | Performance optimization: load testing, bandwidth upgrades, latency optimization. |
| 16-18 | Launch transparency report (aggregate stats, warrant canary history). |

**Deliverables:**
- [ ] 20+ server locations globally
- [ ] Multi-hop VPN
- [ ] Ad/tracker blocking
- [ ] Security audit passed and published
- [ ] Bug bounty program active

---

## 9. Financial Plan

### 9.1 Startup Costs (Phase 0–2, ~6 months)

| Item | Estimated Cost |
|------|---------------|
| Company registration (Panama) | $2,000–$3,000 |
| Registered agent (annual) | $500–$1,000 |
| Bank account setup + initial deposit | $1,000 |
| Domain (10 years) | $120 |
| 5 WG servers × $30/mo × 6 months | $900 |
| Backend server (API/Database) × $40/mo × 6 mo | $240 |
| Monitoring server × $20/mo × 6 mo | $120 |
| Cloudflare Pro | $20/mo × 6 = $120 |
| Design/branding (logo, landing page) | $500–$1,500 |
| Legal (Privacy Policy, ToS) | $1,000–$2,000 |
| **Total startup (6 months)** | **~$7,000–$10,000** |

### 9.2 Monthly Operating Costs (Post-launch, 20 servers)

| Item | Monthly Cost |
|------|-------------|
| 20 WG servers (avg $25/mo) | $500 |
| Backend infrastructure (API, DB, NATS) | $150 |
| Monitoring server | $30 |
| Cloudflare | $20 |
| Registered agent (amortized) | $84 |
| Bug bounty payouts (avg) | $200 |
| SaaS tools (GitHub, email, etc.) | $50 |
| **Total monthly** | **~$1,034** |

### 9.3 Pricing Strategy

> **Current shipping offer** (see `docs/BITCOIN_PAYMENTS_IMPLEMENTATION_PLAN.md`): Free + Premium ($5/mo), **Bitcoin only**. Annual/bi-annual and Monero discount below are deferred.

| Tier | Price | Features |
|------|-------|----------|
| Free | $0 | 2 GB/mo, 5 server locations, 1 device |
| Premium (current) | $5/mo | Unlimited data, all servers, 5 devices — pay with Bitcoin |
| Monthly (legacy name) | $5/mo | Same as Premium |
| Annual | $50/yr ($4.17/mo) | Deferred |
| Bi-annual | $90/2yr ($3.75/mo) | Deferred |

**Crypto payments (current):** Bitcoin via BTCPay Server only.  
**Crypto payments (later):** Monero / Lightning discounts may return after Bitcoin checkout is live.

### 9.4 Break-Even Analysis

```
Monthly costs: ~$1,034
Revenue per paid user: ~$4.50 (blended average across plans)
Users needed to break even: 1,034 / 4.50 ≈ 230 paid users
```

Target: Break-even by **Month 18** after launch (realistic for organic growth with strong differentiators and transparency reputation).

### 9.5 Revenue Projections (Conservative)

| Month After Launch | Paid Users | Monthly Revenue | Cumulative |
|--------------------|-----------|-----------------|------------|
| 1 | 20 | $90 | $90 |
| 3 | 80 | $360 | $540 |
| 6 | 180 | $810 | $2,790 |
| 12 | 400 | $1,800 | $10,620 |
| 18 | 600 | $2,700 | $26,820 |
| 24 | 1,000 | $4,500 | $47,820 |

Marketing channels: Reddit (r/VPN, r/privacy), Hacker News, Twitter/X, privacy-focused podcasts, Techlore/PrivacyTools recommendations, word of mouth.

---

## 10. Roadmap & Timeline

```
2025                   2026
┌───────────────────────┬───────────────────────────────────────────────────────┐
│ Q3     │ Q4     │ Q1     │ Q2     │ Q3     │ Q4     │ Q1     │ Q2     │
├────────┼────────┼────────┼────────┼────────┼────────┼────────┼────────┤
│        │        │        │        │        │        │        │        │
│ Phase 0│ Phase 1│Phase 2 │Phase 3 │Phase 4 │Phase 5 │Phase 6 │Phase 7 │
│████████│████████│████████│████    │████    │        │        │        │
│        │        │        │██      │██      │████████│████████│████████│
│        │        │        │        │        │        │        │        │
│ Found- │ Core   │Server  │CLI     │Desktop │Mobile  │Billing │Scale & │
│ ation  │Backend │Fleet   │Client  │Client  │Clients │& Pay   │Harden  │
│        │        │        │        │        │        │        │        │
├────────┴────────┴────────┴────────┴────────┴────────┴────────┴────────┤
│                                                                        │
│  Key Milestones:                                                       │
│                                                                        │
│  M1 [Month 2]:  Legal entity + Landing page live                       │
│  M2 [Month 4]:  Core backend services operational (dev)                │
│  M3 [Month 6]:  5 production WG servers live                           │
│  M4 [Month 7]:  CLI client working — first VPN connection!             │
│  M5 [Month 10]: Desktop apps (Win/Mac/Linux) released (beta)           │
│  M6 [Month 12]: iOS + Android apps live on App Stores                  │
│  M7 [Month 13]: Payments live — first paid customers                   │
│  M8 [Month 18]: 20+ servers, multi-hop, security audit, break-even     │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

### Phase Dependency Graph

```
Phase 0 (Foundation)
  │
  ▼
Phase 1 (Core Backend)
  │
  ├──────────────────────┐
  ▼                      ▼
Phase 2 (Server Fleet)   Phase 6 (Billing) — depends on Phase 1 only
  │
  ▼
Phase 3 (CLI Client)
  │
  ├──────────────────────┐
  ▼                      ▼
Phase 4 (Desktop)        Phase 5 (Mobile) — both depend on Phase 3
  │                      │
  └──────────┬───────────┘
             ▼
      Phase 7 (Scale & Harden)
```

---

## Appendix A: Repository Structure

```
VeritasVPN/
├── IMPLEMENTATION_PLAN.md          # This document
├── LICENSE                          # AGPL-3.0 for clients, BSL for server
├── README.md                        # Private — internal only
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                   # Build + test all services
│   │   ├── release-cli.yml          # Cross-compile CLI
│   │   ├── release-desktop.yml      # Tauri build matrix
│   │   ├── release-mobile.yml       # Flutter build (Android + iOS)
│   │   └── deploy-infra.yml         # Terraform + Ansible
│   └── CODEOWNERS
├── api/
│   ├── proto/
│   │   ├── auth/v1/auth.proto
│   │   ├── wg/v1/wg.proto
│   │   ├── billing/v1/billing.proto
│   │   └── agent/v1/agent.proto
│   ├── gen/                         # Generated stubs
│   └── buf.gen.yaml
├── services/
│   ├── auth-svc/
│   │   ├── cmd/server/
│   │   ├── internal/...
│   │   ├── migrations/
│   │   ├── Dockerfile
│   │   └── go.mod
│   ├── wg-manager/
│   │   ├── cmd/server/
│   │   ├── internal/...
│   │   ├── migrations/
│   │   ├── Dockerfile
│   │   └── go.mod
│   ├── billing-svc/
│   │   ├── cmd/server/
│   │   ├── internal/...
│   │   ├── migrations/
│   │   ├── Dockerfile
│   │   └── go.mod
│   └── veritas-agent/
│       ├── cmd/agent/
│       ├── internal/...
│       ├── Dockerfile
│       └── go.mod
├── lib/
│   ├── config/
│   ├── logging/
│   ├── crypto/
│   └── jwt/
├── clients/
│   ├── cli/                         # Go CLI
│   │   ├── cmd/
│   │   ├── internal/
│   │   └── go.mod
│   ├── desktop/                     # Tauri 2 + React
│   │   ├── src-tauri/
│   │   ├── src/
│   │   ├── package.json
│   │   └── tauri.conf.json
│   └── mobile/                      # Flutter
│       ├── lib/
│       ├── android/
│       ├── ios/
│       └── pubspec.yaml
├── infra/
│   ├── terraform/
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   ├── outputs.tf
│   │   └── modules/
│   │       └── wg-server/
│   └── ansible/
│       ├── wg-server.yml
│       ├── group_vars/
│       ├── host_vars/
│       ├── roles/
│       │   ├── wireguard/
│       │   ├── unbound/
│       │   └── hardening/
│       └── inventory/
│           ├── production.yml
│           └── staging.yml
├── docker-compose.yml               # Local dev
├── docker-compose.monitoring.yml
└── Makefile
```

## Appendix B: Key Technology Decisions Log

| Decision | Choice | Rationale | Alternatives Considered |
|----------|--------|-----------|------------------------|
| Primary language | Go | Strong concurrency, `wgctrl` library, single binary deploys, good crypto stdlib | Rust (steeper learning curve, smaller VPN ecosystem) |
| Database | PostgreSQL | ACID, JSONB, mature, great Go support (pgx) | MySQL (weaker JSON), CockroachDB (overkill) |
| Cache | Redis | Industry standard, pub/sub, bitmaps for IP allocation, simple | Dragonfly (immature), KeyDB (overkill) |
| Event bus | NATS | 10MB binary, simple ops, JetStream for persistence, no ZK/k8s dependency | Kafka (requires ZK/k8s, heavy for solo), RabbitMQ (heavier) |
| API protocol | gRPC + REST | gRPC for internal, gRPC-gateway for external REST. Strong typing. | GraphQL (overkill for VPN), pure REST (no streaming) |
| Container orchestration | Docker Compose → later k8s | Solo founder can't maintain k8s day 1. Compose → ArgoCD when team grows. | k8s from start (too much overhead) |
| Desktop framework | Tauri 2 | 3MB binary vs 120MB Electron. Rust backend for WG control. Secure. | Electron (bloated), Flutter Desktop (immature), NW.js (dated) |
| Mobile framework | Flutter | Single codebase, mature, good plugin ecosystem for VPN | React Native (bridge overhead), native Swift/Kotlin (more code) |
| VPN protocol | WireGuard only | 4,000 lines of code, auditable, fastest, kernel integration | OpenVPN (bloated, slow), IKEv2 (complex, IPsec overhead) |
| Server OS | Debian 12 | Stable, minimal, great WireGuard support, large community | Alpine (musl issues), Ubuntu (snap bloat), RHEL (license cost) |
| Payment processor | Stripe + BTCPay | Stripe for cards, BTCPay self-hosted for crypto/Monero | Paddle (closed), Coinbase Commerce (US company, privacy poor) |
| DNS | Unbound recursive | No third-party DNS provider sees queries. True privacy. | dnscrypt-proxy (relies on upstream), Pi-hole (only filtering) |

## Appendix C: Risks & Mitigations

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| Solo founder burnout | Medium | High | Strict scope (WG-only). Automate everything. Hire contractor for mobile apps. |
| Server provider terminates service | Low | High | Multi-cloud from day 1. Ansible scripts portable. Hot spares in other providers. |
| Legal challenge / jurisdiction pressure | Low | High | Panama jurisdiction. No logs = nothing to provide. Warrant canary. |
| WireGuard blocked by governments | Medium | Medium | Phase 7: obfuscation layer (WG over WebSocket, shadowsocks wrapper). |
| Payment processor deplatforming | Low | Medium | BTCPay self-hosted. Multiple fiat processors. Monero-first for privacy users. |
| Security vulnerability in WireGuard | Very Low | Critical | WireGuard is <4K lines, heavily audited. Monitor CVE feeds. Quick patch deployment via Ansible. |
| Competitor pricing pressure | Medium | Low | Compete on transparency + open-source, not price. Premium niche. |

---

> **This plan is a living document.** Each phase has its own detailed spec in the `docs/` directory.
> All code in this repo is private until public launch.
> Questions, revisions, and course corrections are expected. Execution over perfection.
