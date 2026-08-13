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
