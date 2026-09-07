# Runbook: VPN node agent enrollment

How a `veritas-agent` proves its identity to `wg-manager`, and how to recover a
node that can no longer register.

## The two credentials

| Credential | Scope | Where it lives | Purpose |
|---|---|---|---|
| `AGENT_AUTH_TOKEN` | Shared by every agent | `veritas-secrets` Secret | Authorizes **first enrollment** of a hostname |
| Per-server agent token | One node | `/var/lib/veritasvpn/agent/token` on the node (hostPath, mode 0600), SHA-256 hash in `servers.agent_token_hash` | Authorizes **everything after** enrollment |

`POST /api/v1/agents/register` requires the bootstrap token every time. If the
hostname is already enrolled it *additionally* requires the node's own agent
token, and does not rotate it.

This second factor is the point. The bootstrap token is one value shared by
every agent pod, so if it alone were sufficient, anyone holding it could
re-register an existing hostname, receive a freshly minted token, and take over
that node's peer stream — including subscriber preshared keys. Requiring the
per-server token means a leaked bootstrap secret can enrol a *new* node but
cannot steal an existing one.

## Registration outcomes

| Situation | Bootstrap token | Agent token | Result |
|---|---|---|---|
| Hostname unknown | valid | ignored | Enrolled; token minted and returned once |
| Hostname known, `agent_token_hash` NULL | valid | ignored | Adopted; token minted and returned once |
| Hostname known and enrolled | valid | matches | Identity refreshed; **token unchanged**, response returns an empty `agent_token` |
| Hostname known and enrolled | valid | missing or wrong | `401` — request rejected and logged |
| Any | invalid | any | `401` |

The agent treats a `401` as permanent and does not retry, so a node that fails
this check stops rather than running in a half-registered state.

## Deploy order

**Roll `veritas-agent` before `wg-manager`.**

The agent sends its token in a new `agent_token` request field. An older
`wg-manager` ignores the unknown field, so a new agent works fine against an old
manager. The reverse does not hold: a new manager will reject an old agent's
re-registration with `401`, because the old agent never sends the field.

The agent DaemonSet uses `updateStrategy: OnDelete`, so rolling it means
deleting the pod after the new image is in the registry.

## Recovery: node cannot register (`401` after a valid bootstrap token)

This means the node lost `/var/lib/veritasvpn/agent/token` while the database
still holds a hash for its hostname. That is intentional: the agent cannot
re-enrol itself, because an attacker who could would be back to the original
vulnerability.

First confirm the token really is gone, on the node:

```sh
sudo ls -l /var/lib/veritasvpn/agent/token
```

If the file exists, do **not** clear the hash — the mismatch means something
else is wrong (wrong hostname, wrong database, or a genuine intrusion).
Investigate before proceeding.

If the file is genuinely lost, an operator clears the stored hash, which returns
the row to the not-yet-enrolled state so the bootstrap token can adopt it again:

```sh
kubectl -n veritas exec deploy/postgres -- \
  psql -U veritas -d veritas -c \
  "UPDATE servers SET agent_token_hash = NULL, agent_token_issued_at = NULL WHERE hostname = '<hostname>';"
```

Then restart the agent so it re-enrols and writes a fresh token file:

```sh
kubectl -n veritas delete pod -l app=veritas-agent
kubectl -n veritas logs -l app=veritas-agent --tail=50 | grep -i enrol
```

Requiring database access for this keeps the break-glass path deliberate and
auditable rather than reachable over the network.

## Rotating the bootstrap token

Rotating `AGENT_AUTH_TOKEN` does not disturb enrolled nodes, since their
per-server tokens are independent. Update the Secret and restart the agents at
the next convenient window.

```sh
kubectl -n veritas patch secret veritas-secrets \
  -p "{\"stringData\":{\"AGENT_AUTH_TOKEN\":\"$(openssl rand -hex 32)\"}}"
kubectl -n veritas rollout restart deploy/wg-manager
```

`wg-manager` must be restarted so it picks up the new value; agents pick it up
whenever they next restart.
