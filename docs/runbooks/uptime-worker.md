# Outside uptime worker

The worker in `monitoring/uptime-worker/` runs on Cloudflare, outside the house network. Every minute it checks the website, API readiness, billing readiness, the egress status file, and a plain TCP connection to the Stealth port. It stores the last result in Workers KV and tells Telegram only after two failures in a row. When the check passes again it sends one recovery message. An alert that stays open is repeated every four hours.

Messages name the check. They do not include the node address.

`/healthz` is not used. It is a static nginx reply.

## Token

Create a Cloudflare API token with:

- Account, Workers Scripts, Edit
- Account, Workers KV Storage, Edit

Store it as the production environment secret `UPTIME_CLOUDFLARE_API_TOKEN`. The production `CLOUDFLARE_API_TOKEN` deploys other Workers and does not cover `veritas-uptime`. The uptime deploy workflow passes `UPTIME_CLOUDFLARE_API_TOKEN` to Wrangler as `CLOUDFLARE_API_TOKEN`. Also add the production environment secret `UPTIME_KV_NAMESPACE_ID` after the namespace exists. The deploy workflow writes that id into `wrangler.toml` at deploy time. The placeholder in git is not a real namespace.

## One-time setup

From `monitoring/uptime-worker/`, with the token in the environment and the account id set:

```bash
npx --yes wrangler@4.31.0 kv namespace create UPTIME_STATE
```

Put the printed namespace id in the GitHub secret `UPTIME_KV_NAMESPACE_ID`.

Set the worker secrets. Do not pass them on a command line that is saved in shell history if you can avoid it. Wrangler prompts for the value.

```bash
npx --yes wrangler@4.31.0 secret put TELEGRAM_BOT_TOKEN
npx --yes wrangler@4.31.0 secret put TELEGRAM_CHAT_ID
npx --yes wrangler@4.31.0 secret put PUBLIC_IP
npx --yes wrangler@4.31.0 secret put TEST_ALERT_SECRET
```

`PUBLIC_IP` is the home address the Stealth listener uses (the same value as the GitHub variable `VERITAS_PUBLIC_IP`). It stays a worker secret. `TEST_ALERT_SECRET` is a long random string you choose.

Deploy once, or push `monitoring/uptime-worker/` to the default branch so `.github/workflows/uptime-worker-deploy.yml` deploys it. Manual dispatch of that workflow also deploys.

## Test alert

After the worker is deployed, send a test message. Replace the host with the workers.dev host wrangler prints. The secret is a header, not a query string.

```bash
curl --silent --show-error --output /dev/null --write-out "%{http_code}\n" \
  --header "x-uptime-test-secret: ${TEST_ALERT_SECRET}" \
  "https://veritas-uptime.<account>.workers.dev/test"
```

`200` means Telegram accepted a message that says this is a test alert. A wrong or missing secret returns `404`.

`POST` or `GET` `/run` with the same header runs the real checks once. Use that after a deliberate failure if you do not want to wait for the next minute.

## What each check means

| Check | Request |
| --- | --- |
| website | `https://veritasvpn.cloud/` |
| readyz | `https://api.veritasvpn.cloud/readyz` |
| billing | `https://api.veritasvpn.cloud/api/v1/billing/readyz` |
| egress | `https://api.veritasvpn.cloud/api/v1/status/egress` must be JSON with `ok: true`. If `checked_at`, `timestamp`, or `updated_at` is present and older than 15 minutes, the check fails. |
| stealth | Plain TCP connect to port 443 on `PUBLIC_IP`, from `cloudflare:sockets` with `secureTransport: "off"`. The check succeeds once `socket.opened` resolves and the listener replies to a TLS ClientHello with a handshake record (first byte `0x16`). The certificate is not verified. The address is not written into the alert. |

Each request times out after about 8 seconds.

## Hourly WireGuard test

`.github/workflows/vpn-e2e.yml` sends a Telegram message when the real UDP test fails. Add repository secrets `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID`. If either secret is unset, the workflow skips the message and does not fail because of that. The log does not print the token.
