# Node bootstrap (WireGuard VPN server)

## Useful information (humans)

Makes this Linux host a real WireGuard VPN server for VeritasVPN.

1. Run once (needs root):

```bash
sudo bash deploy/node/bootstrap-wg.sh
```

2. Set `PUBLIC_IP` in `.env` to this machine’s public IP (or Tailscale IP for testing).

3. Start stack (includes `wg-manager` + `veritas-agent`):

```bash
docker compose up -d --build wg-manager veritas-agent nginx
```

4. Forward **UDP 443** (or **UDP 51820**) on the router to this host for clients off-LAN. Production advertises public UDP **443** while the daemon listens on **51820**.

5. For **Premium port forwarding** (product feature): also forward **TCP/UDP 40000–49999** (or the specific ports users map) from the WAN to this host. Those DNAT into WireGuard peer IPs via nftables (`veritas_pf`). Do not proxy these through Cloudflare HTTP.

6. For **Stealth mode** (WireGuard over TLS/WebSocket): install the host wstunnel service, allow **TCP 443** on the host firewall, and forward **WAN TCP 443** to this host. Keep UDP WireGuard separate (UDP 443 → 51820). Stealth must hit the node **directly** — not via Cloudflare’s HTTP proxy.

```bash
sudo STEALTH_PATH_PREFIX='…' bash deploy/node/install-wstunnel.sh
sudo STEALTH_PORT=443 bash deploy/node/veritas-firewall.sh
```

Then set `STEALTH_ENABLED=true`, `STEALTH_ENDPOINT_HOST`, `STEALTH_ENDPOINT_PORT=443`, and the same `STEALTH_PATH_PREFIX` on wg-manager.

7. Moving to a VPS later: run the same bootstrap, set `PUBLIC_IP` / `EGRESS_IFACE`, point DNS/tunnel at the VPS. The agent/manager contract stays the same.

The authenticated HTTP CONNECT proxy (`veritas-proxy` :1080) is reserved for
the Chrome extension. It accepts only Premium access JWTs; desktop and CLI
clients use WireGuard directly.

## Useful information (AI)

- Bootstrap creates/adopts `wg0`, persists `/etc/wireguard/private.key`, enables IP forward + MASQUERADE.
- Agent runs `network_mode: host` with `NET_ADMIN`, talks to `MANAGER_ENDPOINT=http://127.0.0.1:8082`.
- Peer provisioning: `POST /api/v1/wg/peers` (JWT) → SSE to agent → `wgctrl` AddPeer →
  `POST /api/v1/agents/peers/applied` marks peer `active`. Create responses include `preshared_key`.
- Per-server agent tokens: register with global `AGENT_AUTH_TOKEN` once; wg-manager mints a
  per-server token (returned once as `agent_token`) and stores only the hash. The agent
  persists it to `/var/lib/veritasvpn/agent/token` and uses it as Bearer for heartbeat/SSE/applied/expired.
  To rotate: delete the token file (or re-register the agent) so bootstrap re-mints; the previous
  hash is replaced on successful register.
- Do not put WireGuard on the Mac as the server; Mac is a client only.
- Run only one agent (compose **or** a host binary). Two agents fight over `wg0` and `:9090`.

## Per-device bandwidth cap

The veritas-bandwidth service and its 15-second timer apply an independent 150 Mbps ceiling to every configured WireGuard peer. Download traffic is shaped with HTB + fq_codel on wg0; upload traffic is mirrored to an IFB device (ifb-veritas) and shaped there with HTB + fq_codel. Ingress police was removed because it dropped TCP ACKs and capped upload near 50 Mbps.

The reconciler only rebuilds queues when the peer set or shaping version changes, so active tunnels are not interrupted on ordinary timer runs. The cap is controlled by VERITAS_DEVICE_RATE in the service environment and defaults to 150mbit.

Install script, bandwidth units, and uplink qdisc unit atomically from the repo:

```bash
sudo bash deploy/node/install-host-shaping.sh
```



## WireGuard MTU strategy

Product defaults (intentional, not a bug):

- **Server `wg0`**: MTU **1420** (Ethernet/WG overhead on the node).
- **Issued clients** (desktop / Android): MTU **1280** — safe default for cellular and hostile paths; prefer reliability over peak efficiency.
- **MSS clamp ~1380** on the server remains as-is.

With a ~100 Mbps per-device cap, client/server MTU asymmetry is acceptable. Do not blindly raise client MTU without path testing. See [`docs/MTU_STRATEGY.md`](../../docs/MTU_STRATEGY.md).

## Host firewall

```bash
sudo install -m 0755 deploy/node/veritas-firewall.sh /usr/local/sbin/veritas-firewall
sudo install -m 0644 deploy/node/veritas-firewall.service /etc/systemd/system/veritas-firewall.service
sudo systemctl daemon-reload
sudo systemctl enable --now veritas-firewall.service
```

## SSH hardening

Keys-only sshd drop-in plus fail2ban sshd jail (Tailscale-aware ignore list):

```bash
sudo bash deploy/security/install-ssh-hardening.sh
```

Verify: `sudo sshd -T | grep -E 'passwordauthentication|maxauthtries|x11forwarding'` and `fail2ban-client status sshd`.

WireGuard private key path on k3s nodes: `/etc/wireguard/private.key` (agent hostPath). Bandwidth caps remain host-owned via `veritas-bandwidth.timer` (tc); the agent no longer installs nft meters.

## VPN DNS protection

The WireGuard server provides `10.0.0.1` as the DNS resolver for Android and Linux peers. The agent forwards queries only through encrypted upstream DNS, blocks known malware and phishing domains from automatically refreshed security feeds, and keeps no query names or client identifiers in metrics or logs. The host firewall prevents VPN clients from bypassing the gateway through ordinary DNS (`53`) or DNS-over-TLS (`853`); DNS-over-HTTPS cannot be blocked generically without breaking normal HTTPS traffic.

The active blocklist cache is stored at `/var/lib/veritasvpn/dns/blocklist.txt`. It is not sensitive and is retained so protection continues through a temporary feed outage. Monitor the aggregate `veritas_agent_dns_*` metrics and the `DNSBlocklistStale` / `DNSUpstreamsFailing` alerts.

To test the protection safely while connected to the VPN, open `https://dns-protection-test.veritasvpn.invalid`. The browser should fail to load and Grafana's blocked-DNS count should increase. This is a harmless reserved test name, not a real malicious site.

## Backup metrics

Backup freshness and R2 upload timestamps are exposed through node-exporter’s textfile collector. The metrics directory is intentionally separate from encrypted archives and keys:

```bash
sudo install -m 0644 deploy/systemd/veritas-metrics-dir.service /etc/systemd/system/veritas-metrics-dir.service
sudo systemctl daemon-reload
sudo systemctl enable --now veritas-metrics-dir.service
```
