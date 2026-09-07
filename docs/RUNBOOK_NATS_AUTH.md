# Runbook: NATS authentication

NATS carries account lifecycle events and the `account.teardown` request that
removes a deleted user's live WireGuard peers. It previously accepted any
connection reachable on port 4222, which meant any compromised pod in the
cluster could delete another account's peers. It now requires credentials and
restricts each credential to the subjects it actually needs.

## Credentials

| Secret key | Used by | Rights |
|---|---|---|
| `NATS_AUTH_USER` / `NATS_AUTH_PASSWORD` | auth-svc | Publish `account.registered`, `account.teardown`; subscribe to the three `subscription.*` events |
| `NATS_WG_USER` / `NATS_WG_PASSWORD` | wg-manager | Publish `server.*` and `peer.*`; subscribe to `account.teardown`, `subscription.renewed`, `subscription.expired`. Cannot publish `account.teardown` |
| `NATS_BILLING_USER` / `NATS_BILLING_PASSWORD` | billing-svc | Publish the three `subscription.*` events only. No subscriptions |
| `NATS_NOTIFIER_USER` / `NATS_NOTIFIER_PASSWORD` | telegram-notifier | Subscribe only, and only to `account.registered` and `subscription.renewed` |
| `NATS_USER` / `NATS_PASSWORD` | *(none — legacy)* | Transitional shared credential, removed after the split lands |

Only `account.teardown` deletes live VPN peers, and now only auth-svc can
publish it. Previously any of the three services could, so a compromise of
billing-svc was enough to destroy another account's tunnels.

Both live in the `veritas-secrets` Secret. The NATS StatefulSet reads them as
environment variables; `nats-server.conf` expands `$VAR` at startup. The client
Deployments compose them into `NATS_URL`.

Permissions are enforced by the server, verified against the shipped config:

- an unauthenticated connection gets `Authorization Violation`
- a wrong password gets `Authorization Violation`
- the notifier publishing `account.teardown` gets `Permissions Violation`
- the notifier subscribing to `account.teardown` gets `Permissions Violation`

## First deploy: create the notifier credential

`NATS_NOTIFIER_USER` and `NATS_NOTIFIER_PASSWORD` are new. **NATS and
telegram-notifier will not start without them**, so add them before applying:

```sh
kubectl -n veritas patch secret veritas-secrets -p "$(cat <<EOF
{"stringData":{
  "NATS_NOTIFIER_USER":"notifier",
  "NATS_NOTIFIER_PASSWORD":"$(openssl rand -hex 32)"
}}
EOF
)"
```

Confirm all four keys are present before rolling anything:

```sh
kubectl -n veritas get secret veritas-secrets -o json \
  | jq -r '.data | keys[]' | grep NATS
```

Expect `NATS_NOTIFIER_PASSWORD`, `NATS_NOTIFIER_USER`, `NATS_PASSWORD`, `NATS_USER`.

## Deploy order

NATS must be restarted before its clients, because the clients were already
sending credentials the server ignored. Restarting NATS is what starts enforcing
them; the clients need no change.

```sh
kubectl -n veritas apply -f deploy/k8s/base/nats-configmap.yaml
kubectl -n veritas apply -f deploy/k8s/base/nats.yaml
kubectl -n veritas rollout restart statefulset/nats
kubectl -n veritas rollout status statefulset/nats

kubectl -n veritas apply -f deploy/k8s/base/telegram-notifier.yaml
kubectl -n veritas rollout status deploy/telegram-notifier
```

## Verifying after deploy

Authorization violations appear in the NATS log, so the fastest check is that
there are none:

```sh
kubectl -n veritas logs statefulset/nats --tail=100 | grep -i "violation" || echo "no violations"
```

Then confirm the subscribers actually attached:

```sh
kubectl -n veritas logs deploy/auth-svc  --tail=50 | grep -i "subscription events"
kubectl -n veritas logs deploy/wg-manager --tail=50 | grep -i "teardown"
kubectl -n veritas logs deploy/telegram-notifier --tail=50
```

An end-to-end check is to delete a disposable test account and confirm its peer
disappears. Account deletion now fails closed: if wg-manager does not
acknowledge the teardown, the delete is refused rather than leaving a live
tunnel behind.

## Rotating

```sh
kubectl -n veritas patch secret veritas-secrets \
  -p "{\"stringData\":{\"NATS_PASSWORD\":\"$(openssl rand -hex 32)\"}}"
kubectl -n veritas rollout restart statefulset/nats
kubectl -n veritas rollout restart deploy/auth-svc deploy/wg-manager deploy/billing-svc
```

Restart NATS first, then the clients. There is a brief window where clients
cannot connect; auth-svc will refuse account deletions during it, which is the
intended fail-closed behaviour.

## Splitting the shared credential

The three services used to share one credential whose permissions were the union
of what all three needed. They now have one each. Adding them takes six new
Secret keys, which must exist before NATS restarts or the server will fail to
expand `$NATS_AUTH_USER` and refuse to start:

```sh
kubectl -n veritas patch secret veritas-secrets -p "$(cat <<EOF
{"stringData":{
  "NATS_AUTH_USER":"auth-svc",       "NATS_AUTH_PASSWORD":"$(openssl rand -hex 24)",
  "NATS_WG_USER":"wg-manager",       "NATS_WG_PASSWORD":"$(openssl rand -hex 24)",
  "NATS_BILLING_USER":"billing-svc", "NATS_BILLING_PASSWORD":"$(openssl rand -hex 24)"
}}
EOF
)"
```

`nats-server.conf` deliberately keeps the old shared `$NATS_USER` entry valid for
one deploy. NATS and the Deployments do not restart in a guaranteed order, so
without it a pod that has not rolled yet would be rejected. Once every service
logs a connection as its own user, remove the `SHARED_LEGACY_PERMS` block and its
`users` entry, redeploy, and drop `NATS_USER` / `NATS_PASSWORD` from the Secret.

Confirm who is actually connected:

```sh
kubectl -n veritas exec nats-0 -- wget -qO- 'http://localhost:8222/connz?auth=1' \
  | grep -o '"authorized_user":"[^"]*"' | sort | uniq -c
```

Expect one connection each for `auth-svc`, `wg-manager`, `billing-svc` and
`notifier`, and none for the legacy shared user.
