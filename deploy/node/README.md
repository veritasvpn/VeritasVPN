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

4. Forward **UDP 51820** on the router to this host for clients off-LAN.

5. Moving to a VPS later: run the same bootstrap, set `PUBLIC_IP` / `EGRESS_IFACE`, point DNS/tunnel at the VPS. The agent/manager contract stays the same.

SOCKS (`veritas-proxy` :1080) remains for the Chrome extension only. Desktop/CLI use WireGuard.

## Useful information (AI)

- Bootstrap creates/adopts `wg0`, persists `/etc/wireguard/private.key`, enables IP forward + MASQUERADE.
- Agent runs `network_mode: host` with `NET_ADMIN`, talks to `MANAGER_ENDPOINT=http://127.0.0.1:8082`.
- Peer provisioning: `POST /api/v1/wg/peers` (JWT) → SSE to agent → `wgctrl` AddPeer →
  `POST /api/v1/agents/peers/applied` marks peer `active`. Create responses include `preshared_key`.
- Do not put WireGuard on the Mac as the server; Mac is a client only.
- Run only one agent (compose **or** a host binary). Two agents fight over `wg0` and `:9090`.

## Per-device bandwidth cap

The veritas-bandwidth service and its 15-second timer apply an independent 50 Mbps ceiling to every configured WireGuard peer. Download traffic is shaped with an HTB class and fq_codel leaf on wg0; upload traffic is policed by the peer's /32 source address. The reconciler only rebuilds queues when the peer set changes, so active tunnels are not interrupted on ordinary timer runs.

The cap is controlled by VERITAS_DEVICE_RATE in the service environment and defaults to 50mbit. The runtime installer is:

sudo install -m 0755 deploy/node/veritas-bandwidth.sh /usr/local/sbin/veritas-bandwidth.sh
sudo install -m 0644 deploy/systemd/veritas-bandwidth.service /etc/systemd/system/veritas-bandwidth.service
sudo install -m 0644 deploy/systemd/veritas-bandwidth.timer /etc/systemd/system/veritas-bandwidth.timer
sudo systemctl daemon-reload
sudo systemctl enable --now veritas-bandwidth.timer


## Host firewall

```bash
sudo install -m 0755 deploy/node/veritas-firewall.sh /usr/local/sbin/veritas-firewall
sudo install -m 0644 deploy/node/veritas-firewall.service /etc/systemd/system/veritas-firewall.service
sudo systemctl daemon-reload
sudo systemctl enable --now veritas-firewall.service
```

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
