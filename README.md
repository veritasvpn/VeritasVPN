# VeritasVPN

> Privacy is truth. WireGuard-only VPN (SOCKS only where WireGuard cannot run, e.g. Chrome).

## Architecture

```
Desktop / CLI  --WireGuard-->  linux node wg0 (veritas-agent)
                     ^
Website / API  -->  nginx --> auth-svc, billing-svc, wg-manager
                                      |
                               SSE peer updates --> agent
```

The **VPN server is the Linux node** (today `linuxDesktop`, later a VPS). Your Mac is a client only.

## Quick Start (VPN node)

```bash
sudo bash deploy/node/bootstrap-wg.sh   # optional if agent brings up wg0 itself
# set PUBLIC_IP in .env to this host's public IP
docker compose up -d --build
```

Services:
- **auth-svc** → `:8081`
- **wg-manager** → `:8082` — peer provisioning + agent API
- **billing-svc** → `:8083`
- **veritas-agent** — host WireGuard (`wg0`, UDP `51820`)
- **veritas-proxy** → `:1080` — SOCKS for Chrome extension only
- **nginx** → `:8000`

Forward **UDP 51820** on the router for remote clients.

## Desktop

Connect uses WireGuard against the node (bundled wireguard-go on macOS desktop, or `wg-quick` on CLI). If the WireGuard engine is missing from the desktop bundle, it falls back to SOCKS.

## CLI

```bash
export VERITAS_API_URL=https://veritasvpn.cloud/api/v1
export VERITAS_ACCESS_TOKEN=...
veritas connect
veritas disconnect
```

## Building

```bash
make build-all
make test
```

## License

Licensed under the **Business Source License 1.1** — see [`LICENSE`](./LICENSE).

- Source is publicly available on GitHub.
- Production use that offers a competing commercial VPN to third parties requires a separate commercial license (see Additional Use Grant).
- On the Change Date (`2030-07-28`), the Change License (GPL-3.0-or-later) applies to that version.
