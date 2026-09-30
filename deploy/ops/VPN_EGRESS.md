# VPN egress on `/status`

`/status` reads `GET https://api.veritasvpn.cloud/api/v1/status/egress`. That JSON is produced on the production host. It is separate from `GET /healthz` (API up) and from `/api/check/ip` (the visitor's address). The egress row can be red while API health stays green.

The website treats `checked_at` older than 15 minutes as degraded. Publish every 5 minutes.

## What the probe checks

`deploy/ops/verify-vpn-egress.sh` uses the gateway NAT path. It does not open a client tunnel.

1. `VERITAS_PUBLIC_IP` is a public IPv4 address.
2. The WireGuard interface is up (`WG_INTERFACE`, default `wg0`).
3. nftables table `veritas` has masquerade from that interface to the uplink (the same NAT rule client traffic uses).
4. An IPv4 echo bound to that uplink (`curl --interface`) matches `VERITAS_PUBLIC_IP`.

Echo URLs default to `https://api.ipify.org`, `https://ipv4.icanhazip.com`, and `https://ifconfig.me/ip`.

JSON written to `/var/lib/veritasvpn/status/egress.json`:

```json
{"ok":true,"observed_ip":"203.0.113.10","expected_ip":"203.0.113.10","checked_at":"2026-09-30T17:00:00Z","error":""}
```

`ok` is false on mismatch, a down tunnel, or a missing masquerade rule. The file is still written. nginx returns that body with HTTP 200. `/healthz` is a different location and stays `200 ok`.

## Publish path

The API nginx pod mounts the host directory read-only and serves the file at `location = /api/v1/status/egress`. The status page on Cloudflare Pages is allowed to read it (`connect-src` already includes `https://api.veritasvpn.cloud`).

After this commit is on the host, apply the **site** overlay so the nginx pod picks up the location and the mount. Do not apply `overlays/k3s` directly (`deploy/k8s/overlays/SITE_LOCAL.md`).

```bash
sudo /opt/veritasvpn/deploy/k8s/scripts/apply.sh site
```

The deployment spec changes (new volume), so the nginx pod restarts and loads the ConfigMap. Confirm:

```bash
curl -fsS https://api.veritasvpn.cloud/api/v1/status/egress
```

nginx runs as uid 101. The script creates the directory mode `755` and the file mode `644`. If the parent `/var/lib/veritasvpn` is not traversable by uid 101, the endpoint returns 403 while `/healthz` stays green.

## Manual run on Dell

```bash
sudo install -d -m 0755 /etc/veritasvpn
sudo cp /opt/veritasvpn/deploy/ops/egress.env.example /etc/veritasvpn/egress.env
sudoedit /etc/veritasvpn/egress.env   # set VERITAS_PUBLIC_IP
sudo chmod 600 /etc/veritasvpn/egress.env
sudo chown root:root /etc/veritasvpn/egress.env
sudo bash /opt/veritasvpn/deploy/ops/verify-vpn-egress.sh
```

Optional variables: `EGRESS_IFACE`, `WG_INTERFACE`, `STATUS_EGRESS_PATH`, `IP_ECHO_URLS`, `VERITAS_EGRESS_ENV`. `PUBLIC_IP` and `EXPECTED_EGRESS_IP` are used only when `VERITAS_PUBLIC_IP` is unset.

`/etc/veritasvpn/egress.env` is a list of assignments (`KEY=value`, optional quotes or `export`). It is not a shell script. When cron runs as root, the file must be owned by root and must not be group or world writable.

## Cron

The agent does not SSH to Dell. Cron does nothing until the host installs it.

```cron
*/5 * * * * root /opt/veritasvpn/deploy/ops/verify-vpn-egress.sh >> /var/log/veritas-egress.log 2>&1
```

That line is also in `deploy/cron/veritas-crontab`. The script sets `PATH` and reads `/etc/veritasvpn/egress.env`, so a minimal cron environment is enough. Install only this job (the rest of that crontab is the older backup schedule):

```bash
printf '%s\n' '*/5 * * * * root /opt/veritasvpn/deploy/ops/verify-vpn-egress.sh >> /var/log/veritas-egress.log 2>&1' \
  | sudo tee /etc/cron.d/veritas-vpn-egress >/dev/null
sudo chown root:root /etc/cron.d/veritas-vpn-egress
sudo chmod 644 /etc/cron.d/veritas-vpn-egress
```

## Fallback: real client tunnel

The gateway echo proves the uplink IP and that the masquerade rule is installed. It does not send a packet in from a WireGuard peer. When clients still exit wrong, run the tunnel-hold probe (root, `wg-quick`, `VERITAS_E2E_ACCOUNT_ID` in `/etc/veritasvpn/e2e.env`):

```bash
sudo bash /opt/veritasvpn/deploy/verify/tunnel-hold-e2e.sh
```

That compares `https://api.ipify.org` through the tunnel to the peer endpoint, same check as `deploy/verify/external-wireguard-e2e.sh`.
