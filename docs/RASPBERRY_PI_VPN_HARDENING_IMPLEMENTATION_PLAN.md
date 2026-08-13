# VeritasVPN Raspberry Pi Hardening and Reliability Plan

> **Status:** Planning only — no changes have been implemented.
>
> **Objective:** Harden the current Raspberry Pi deployment, make WireGuard startup deterministic, protect application data and secrets, improve recovery and monitoring, and retire obsolete VPN-related configuration from `linuxDesktop`.
>
> **Primary constraint:** Keep the currently working VPN available and avoid reintroducing Pi-hole or unrelated workloads on the Raspberry Pi.

---

## 1. Current State

This plan is based on a read-only audit performed on 2026-07-30.

### Raspberry Pi (`jpg-raspberryPi`)

- WireGuard interface `wg0` is active and listening on UDP `51820`.
- Three WireGuard peers are configured on `10.0.0.0/24`.
- A NAT masquerade rule sends VPN client traffic through `eth0`.
- `wg-quick@wg0` is disabled and inactive.
- The Veritas agent appears to own or recreate the WireGuard interface.
- Docker and Tailscale are enabled and running.
- Pi-hole is inactive and must remain inactive.
- The following Docker services are running:
  - `postgres`
  - `redis`
  - `nats`
  - `auth-svc`
  - `wg-manager`
  - `billing-svc`
  - `veritas-agent`
  - `nginx`
  - `cloudflared`
- PostgreSQL, Redis, NATS, internal APIs, and Nginx are currently published on host interfaces.

### Linux PC (`linuxDesktop`)

- No WireGuard interface or VeritasVPN container is active.
- No VeritasVPN container remains in Docker, including stopped containers.
- Tailscale is active and enabled.
- `openvpn.service` is enabled but only `active (exited)`.
- No OpenVPN profile, process, or tunnel interface exists.

### Relevant Repository Behavior

- `docker-compose.yml` publishes internal ports to the host.
- `docker-compose.pi.yml` currently adds only `restart: unless-stopped`.
- `veritas-agent` uses host networking and elevated network privileges.
- WireGuard state is mounted from `./data/wireguard`.
- PostgreSQL uses the named volume `pgdata`.
- Redis persistence is disabled.
- NATS JetStream is enabled but has no persistent volume.
- `cloudflared` uses the floating `latest` tag.
- Several service defaults still identify the environment as development.

---

## 2. Target Architecture

The Raspberry Pi remains the only VPN server and Veritas backend node.

### Publicly reachable

- UDP `51820` → WireGuard
- Cloudflare Tunnel → approved HTTP application routes

### Administratively reachable

- SSH through Tailscale
- Optional SSH from a specifically approved LAN management subnet

### Docker-internal only

- PostgreSQL `5432`
- Redis `6379`
- NATS `4222`
- NATS monitoring `8222`
- Auth service `8080`
- WireGuard manager `8080`
- Billing service `8080`

### Host-network exception

- `veritas-agent`, because it must manage the host WireGuard interface and firewall.

### Explicitly excluded from the Pi

- Pi-hole
- General-purpose desktop workloads
- Development tools not required at runtime
- The public static website, except for an intentional API/origin fallback if still required

---

## 3. Safety Principles

1. Export the current working state before making changes.
2. Never rotate server WireGuard keys and application secrets in the same maintenance window.
3. Change one infrastructure layer at a time.
4. Maintain a tested rollback command or file for every phase.
5. Validate from both the LAN and an external network.
6. Do not remove the existing SSH path until Tailscale administration is verified.
7. Do not reboot until configuration validation and backups succeed.
8. Do not expose databases temporarily for convenience; use `docker compose exec`, an SSH tunnel, or a short-lived localhost binding.

---

## 4. Phase 0 — Inventory, Backups, and Recovery Baseline

### Tasks

- [ ] Record the Pi OS version, kernel, architecture, Docker version, Compose version, free disk space, memory, temperature, and boot storage type.
- [ ] Record the Pi LAN IP, Tailscale IP, default gateway, egress interface, and router UDP `51820` forwarding target.
- [ ] Export the effective Compose configuration with secrets redacted.
- [ ] Record all running container image IDs and digests.
- [ ] Export:
  - [ ] `wg show`
  - [ ] `ip address show wg0`
  - [ ] `ip route`
  - [ ] IPv4 and IPv6 forwarding values
  - [ ] Current `iptables` and/or `nftables` rules
  - [ ] Enabled systemd services
- [ ] Back up the WireGuard private key and configuration with owner-only permissions.
- [ ] Create a PostgreSQL logical backup using `pg_dump`.
- [ ] Record PostgreSQL migration state.
- [ ] Back up NATS data if JetStream is carrying durable application data.
- [ ] Export the current Cloudflare Tunnel configuration without printing its token.
- [ ] Confirm that the repository, deployment `.env`, and runtime data locations are known.
- [ ] Write a one-page bare-metal recovery runbook.

### Deliverables

- Encrypted off-device backup archive
- Redacted state report
- Recovery runbook
- Confirmed maintenance window

### Acceptance criteria

- A PostgreSQL backup can be restored into a temporary database.
- The WireGuard private key backup is readable only by its owner.
- The Pi can still be reached through both LAN SSH and Tailscale before firewall work begins.

---

## 5. Phase 1 — Remove Unnecessary Host Port Publishing

### Repository changes

Create a production-focused Compose configuration instead of relying on development port mappings.

- [ ] Remove host `ports` mappings for:
  - [ ] PostgreSQL `5432`
  - [ ] Redis `6379`
  - [ ] NATS `4222`
  - [ ] NATS monitoring `8222`
  - [ ] Auth service `8081`
  - [ ] WireGuard manager `8082`
  - [ ] Billing service `8083`
- [ ] Keep these services on the private Compose network.
- [ ] Let Nginx reach application services by Compose DNS name.
- [ ] Let `cloudflared` reach Nginx through the Compose network.
- [ ] Decide whether Nginx needs any host binding:
  - Preferred: no host binding when Cloudflare Tunnel is the sole HTTP entry point.
  - Optional LAN diagnostics: bind only to `127.0.0.1` or the Pi's specific management address.
- [ ] Keep `veritas-agent` on host networking.
- [ ] Confirm `MANAGER_ENDPOINT` remains reachable from the host-networked agent.
- [ ] Add an explicit internal network and mark it `internal: true` where outbound access is not required.

### Pre-deployment validation

- [ ] Run `docker compose config`.
- [ ] Build all custom images.
- [ ] Verify Nginx upstream names and ports.
- [ ] Verify Cloudflare Tunnel ingress targets.
- [ ] Confirm no application depends on connecting to PostgreSQL, Redis, or NATS through the Pi host IP.

### Acceptance criteria

- Only intentionally approved ports appear in `ss -lntup`.
- Auth, billing, and WireGuard provisioning still work through Nginx/Cloudflare.
- Database, Redis, and NATS remain reachable from their dependent containers.
- They are not reachable from another device on the LAN.

### Rollback

- Restore the previous Compose files and redeploy the previously recorded image digests.

---

## 6. Phase 2 — Host Firewall and Router Exposure

### Design

Use one firewall owner. Prefer `nftables` on modern Raspberry Pi OS; use UFW only if it is already the established firewall manager. Avoid maintaining overlapping UFW, raw `iptables`, and `nftables` policies.

### Required policy

- [ ] Default-deny unsolicited inbound traffic.
- [ ] Permit established and related traffic.
- [ ] Permit loopback traffic.
- [ ] Permit WireGuard UDP `51820`.
- [ ] Permit SSH on `tailscale0`.
- [ ] Optionally permit SSH from a specific LAN management subnet.
- [ ] Permit forwarding from `wg0` to the egress interface.
- [ ] Permit return traffic to `wg0`.
- [ ] Apply NAT masquerading for `10.0.0.0/24` through the egress interface.
- [ ] Block direct LAN/WAN access to PostgreSQL, Redis, NATS, internal APIs, and Nginx unless explicitly required.
- [ ] Confirm the router forwards only UDP `51820` to the Pi for the VPN.
- [ ] Remove obsolete router forwards for the Linux PC and old proxy ports.

### Safe rollout sequence

1. Keep an existing SSH session open.
2. Schedule an automatic firewall rollback before applying the new rules.
3. Apply the candidate rules.
4. Open a second SSH session through Tailscale.
5. Test WireGuard externally.
6. Cancel the scheduled rollback only after all tests pass.
7. Persist the validated rules.

### Acceptance criteria

- External WireGuard clients connect and can reach the internet.
- Tailscale SSH access remains available.
- Internal service ports fail closed from the LAN.
- Cloudflare public routes remain healthy.
- Rules survive a controlled reboot.

---

## 7. Phase 3 — Deterministic WireGuard Ownership and Boot Recovery

### Decision

Choose exactly one interface owner:

#### Recommended for the current architecture

Keep `veritas-agent` as the WireGuard owner because dynamic peer provisioning already flows through the manager and agent.

### Tasks

- [ ] Document that `wg-quick@wg0` must remain disabled to prevent two owners from fighting over `wg0`.
- [ ] Confirm `veritas-agent` performs all required startup actions:
  - [ ] Create or adopt `wg0`
  - [ ] Apply the server private key
  - [ ] Assign `10.0.0.1/24`
  - [ ] Listen on UDP `51820`
  - [ ] Enable the interface
  - [ ] Enable IPv4 forwarding
  - [ ] Apply forwarding and NAT rules idempotently
  - [ ] Reconcile peers from authoritative storage
- [ ] Add an agent health check that verifies the interface, port, address, forwarding, and manager connectivity.
- [ ] Ensure Docker starts after local networking is online.
- [ ] Ensure the Veritas stack has a single supported boot command or systemd wrapper.
- [ ] Ensure only one agent instance can run.
- [ ] Add a boot-time verification service or timer that reports failure without creating a second WireGuard owner.

### Reboot test

- [ ] Record the pre-reboot peer list.
- [ ] Reboot the Pi during a maintenance window.
- [ ] Verify Docker and required containers recover automatically.
- [ ] Verify `wg0`, NAT, routes, and peers are restored.
- [ ] Connect from an external network.
- [ ] Create and revoke a test peer.
- [ ] Confirm existing peers still function.

### Rollback

- Restore the saved firewall rules, WireGuard state, and prior container images.
- Use the documented manual bootstrap only if the agent cannot recover `wg0`.

---

## 8. Phase 4 — PostgreSQL Protection and Backups

### Network and authentication

- [ ] Keep PostgreSQL on the private Docker network only.
- [ ] Use a dedicated database user per service where practical.
- [ ] Grant each user only the required schemas and operations.
- [ ] Review authentication rules and reject broad network ranges.
- [ ] Replace the shared development password.
- [ ] Remove fallback credentials such as `change-me-set-in-env-file`.

### Backup design

- [ ] Run an automated daily logical backup.
- [ ] Encrypt backups before they leave the Pi.
- [ ] Copy backups to storage that is not physically on the Pi.
- [ ] Suggested retention:
  - [ ] 7 daily backups
  - [ ] 4 weekly backups
  - [ ] 3 monthly backups
- [ ] Alert when a backup fails or becomes older than the allowed recovery point.
- [ ] Perform a scheduled restore test at least monthly.
- [ ] Document the recovery time objective and recovery point objective.

### Storage

- [ ] Determine whether `pgdata` is on microSD or SSD.
- [ ] Move production database storage to a reliable SSD if it currently resides on microSD.
- [ ] Confirm filesystem health and free-space alerting.

### Acceptance criteria

- PostgreSQL is unreachable from the LAN.
- Applications authenticate with rotated credentials.
- A fresh database can be restored from the off-device backup.

---

## 9. Phase 5 — Redis and NATS Hardening

### Redis

- [ ] Keep Redis internal to Docker.
- [ ] Enable ACL-based authentication.
- [ ] Create an application-specific user with only necessary commands.
- [ ] Set an explicit memory limit and eviction policy.
- [ ] Decide whether Redis data is disposable:
  - If disposable, document that behavior and keep persistence disabled.
  - If required for recovery, enable an appropriate persistence mode and back it up.
- [ ] Add a health check that authenticates.

### NATS

- [ ] Keep client and monitoring ports internal to Docker.
- [ ] Enable credentials or account-based authentication.
- [ ] Restrict monitoring access.
- [ ] Add a persistent volume for JetStream if durable events are required.
- [ ] Define JetStream storage limits and retention.
- [ ] Add a health check that verifies server readiness.

### Acceptance criteria

- Unauthenticated application connections fail.
- Authorized services reconnect successfully.
- Required events survive a container restart if durability is part of the design.

---

## 10. Phase 6 — Secrets and Production Configuration

### Inventory and rotation

- [ ] Inventory all secrets without printing their values:
  - [ ] Pi account password
  - [ ] Router administrator password
  - [ ] SSH keys
  - [ ] WireGuard server and peer keys
  - [ ] `JWT_SECRET`
  - [ ] `AGENT_AUTH_TOKEN`
  - [ ] `DB_PASSWORD`
  - [ ] Redis and NATS credentials
  - [ ] Cloudflare Tunnel token
  - [ ] BTCPay credentials and webhook secret
- [ ] Rotate credentials that have been shared interactively or stored insecurely.
- [ ] Rotate one dependency at a time.
- [ ] Do not rotate the WireGuard server key unless compromise is suspected; doing so invalidates existing client configurations.

### Storage

- [ ] Keep the production `.env` outside Git.
- [ ] Set restrictive ownership and permissions.
- [ ] Prefer Docker secrets or mounted root-readable secret files for production credentials.
- [ ] Ensure container logs never print secrets or complete client configurations.
- [ ] Add automated secret scanning to the repository and CI.

### Production settings

- [ ] Set `ENVIRONMENT=production`.
- [ ] Set structured JSON logging.
- [ ] Reduce production log level from debug to info or warning.
- [ ] Configure exact HTTPS CORS origins.
- [ ] Confirm mock billing is disabled.
- [ ] Remove development URLs and fallback credentials.

### Acceptance criteria

- The application starts with no secret defaults.
- Repository scanning finds no production credentials.
- Old credentials no longer authenticate.

---

## 11. Phase 7 — Container Reliability and Supply-Chain Controls

### Tasks

- [ ] Add meaningful health checks to every required service.
- [ ] Make startup dependencies health-based where appropriate.
- [ ] Retain `restart: unless-stopped`.
- [ ] Add graceful shutdown periods.
- [ ] Configure Docker log rotation.
- [ ] Define memory and CPU limits appropriate for the Pi.
- [ ] Set PostgreSQL shared-memory requirements deliberately.
- [ ] Pin third-party images to tested versions or immutable digests:
  - [ ] PostgreSQL
  - [ ] Redis
  - [ ] NATS
  - [ ] Nginx
  - [ ] Cloudflared
- [ ] Stop using `cloudflare/cloudflared:latest`.
- [ ] Add image vulnerability scanning.
- [ ] Rebuild custom Go services with reproducible dependency versions.
- [ ] Run containers as non-root wherever host-network management does not require elevated access.
- [ ] Drop unnecessary Linux capabilities.
- [ ] Mark filesystems read-only where practical.
- [ ] Add `no-new-privileges` where compatible.

### Acceptance criteria

- A failed service is detected and recovers predictably.
- One runaway container cannot exhaust all Pi memory.
- Deployments use known image digests.
- Normal application containers do not run privileged.

---

## 12. Phase 8 — Cloudflare and Public API Hardening

### Tasks

- [ ] Document every public hostname and its intended upstream.
- [ ] Ensure Cloudflare Tunnel targets only Nginx, not databases or internal service ports.
- [ ] Add a final catch-all ingress rule that returns an error.
- [ ] Protect administrative routes with Cloudflare Access.
- [ ] Validate Cloudflare Access tokens at the origin for protected routes where applicable.
- [ ] Apply rate limits to authentication, peer provisioning, and billing endpoints.
- [ ] Configure request body limits and timeouts in Nginx.
- [ ] Add security headers appropriate for the website and API.
- [ ] Confirm the Pi's origin IP and HTTP ports are not publicly exposed.
- [ ] Run only the intended Cloudflare connector; avoid accidental duplicate fallback and Kubernetes connectors using the same routing configuration.

### Acceptance criteria

- Public hostnames expose only documented routes.
- Administrative endpoints require identity-aware access.
- Direct access to the Pi origin is blocked.

---

## 13. Phase 9 — Monitoring and Alerting

### Host monitoring

- [ ] CPU load
- [ ] Memory and swap
- [ ] Disk usage and inode usage
- [ ] Temperature and throttling
- [ ] SSD/microSD health indicators
- [ ] Network availability
- [ ] Reboot and uptime status

### VPN monitoring

- [ ] WireGuard interface presence
- [ ] UDP `51820` listener
- [ ] Recent peer handshake age
- [ ] Transfer counters
- [ ] Forwarding and NAT rule presence
- [ ] External connection probe from outside the LAN

### Application monitoring

- [ ] Container health and restart count
- [ ] Public API health
- [ ] Cloudflare Tunnel connectivity
- [ ] PostgreSQL availability and backup age
- [ ] Redis and NATS availability
- [ ] Authentication and provisioning error rates
- [ ] Disk growth by Docker volumes and logs

### Alerting

- [ ] Send alerts through a channel independent of the Pi.
- [ ] Define warning and critical thresholds.
- [ ] Avoid including keys, tokens, peer configurations, or personal data in alerts.

---

## 14. Phase 10 — Linux PC Cleanup

Perform this only after the Pi passes the reboot and external VPN tests.

- [ ] Disable the inert `openvpn.service`.
- [ ] Leave Tailscale enabled only if remote access to `linuxDesktop` is still required.
- [ ] Determine whether Docker supports any unrelated workloads.
- [ ] If Docker is unused, disable its automatic startup without deleting user data.
- [ ] Remove obsolete Veritas runtime directories only after backing them up and confirming they are not the authoritative source.
- [ ] Remove old router forwarding rules that target `linuxDesktop`.
- [ ] Verify the Linux PC has no WireGuard, OpenVPN, Veritas proxy, backend, or database listeners after reboot.

### Acceptance criteria

- Restarting `linuxDesktop` cannot accidentally bring up an obsolete VPN service.
- Tailscale remains available if intentionally retained.
- The Raspberry Pi is the documented and observable production VPN owner.

---

## 15. Phase 11 — Disaster Recovery and Hardware Resilience

- [ ] Use an SSD for persistent production data.
- [ ] Add a small UPS suitable for the Pi and network equipment.
- [ ] Configure safe shutdown on extended power loss if supported.
- [ ] Keep a spare boot device or documented re-image procedure.
- [ ] Maintain an encrypted off-device copy of:
  - [ ] Deployment configuration
  - [ ] Database backups
  - [ ] WireGuard server key
  - [ ] Required secret inventory and recovery instructions
- [ ] Test a clean rebuild on spare hardware or a temporary VM.
- [ ] Record expected recovery time.

---

## 16. Recommended Execution Order

| Order | Phase | Risk | Expected outage |
|---:|---|---|---|
| 1 | Inventory and recovery baseline | Low | None |
| 2 | Remove internal host port publishing | Medium | Brief container restart |
| 3 | Firewall and router exposure | High | Maintenance window required |
| 4 | Deterministic WireGuard boot recovery | High | Controlled reboot |
| 5 | PostgreSQL protection and backups | Medium | Possibly brief |
| 6 | Redis and NATS hardening | Medium | Service restart |
| 7 | Secret rotation and production settings | High | Coordinated restart |
| 8 | Container reliability and image pinning | Medium | Rolling restart |
| 9 | Cloudflare and public API hardening | Medium | Route-by-route |
| 10 | Monitoring | Low | None |
| 11 | Linux PC cleanup | Low | No VPN outage |
| 12 | Hardware and disaster recovery | Medium | Planned migration |

---

## 17. Final Validation Checklist

- [ ] The Pi is the only VeritasVPN server.
- [ ] Pi-hole remains inactive and is not a dependency.
- [ ] WireGuard connects from an external network.
- [ ] VPN clients can resolve DNS and reach the internet.
- [ ] Existing and newly provisioned peers work.
- [ ] Peer revocation works.
- [ ] Only approved host ports are listening.
- [ ] PostgreSQL, Redis, and NATS are unreachable from the LAN.
- [ ] Cloudflare public routes work and undocumented routes fail closed.
- [ ] SSH works through Tailscale.
- [ ] Required services recover after a Pi reboot.
- [ ] A PostgreSQL backup restore succeeds.
- [ ] Alerts are received when a controlled health check is failed.
- [ ] No production secrets exist in Git.
- [ ] `linuxDesktop` does not start a legacy VPN service.
- [ ] Recovery documentation has been tested by rebuilding or restoring into a clean environment.

---

## 18. Change Record Template

Use this for every implementation phase:

```text
Phase:
Date:
Operator:
Maintenance window:
Pre-change backup:
Files changed:
Commands executed:
Expected result:
Observed result:
Validation completed:
Rollback required:
Rollback result:
Follow-up actions:
```

