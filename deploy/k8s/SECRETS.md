# Kubernetes secrets (do not kustomize-apply)

Production secrets are **cluster-managed**. Incomplete `secrets.yaml` files must
never appear in `kustomization.yaml` `resources:` — applying them can wipe live
keys (`TURNSTILE_SECRET_KEY`, `STEALTH_PATH_PREFIX`, BTCPay API keys, etc.).

## Veritas (`veritas` namespace)

```bash
# Create once (example — use real values from your vault)
kubectl -n veritas create secret generic veritas-secrets \
  --from-literal=JWT_SECRET='...' \
  --from-literal=DATABASE_URL='...' \
  --from-literal=REDIS_PASSWORD='...' \
  # ... other keys

# Patch a single key without replacing the Secret
kubectl -n veritas patch secret veritas-secrets --type merge \
  -p '{"stringData":{"TURNSTILE_SECRET_KEY":"..."}}'
```

Example templates (not applied by default): `deploy/k8s/base/secrets.example.yaml`.

## BTCPay mainnet (`btcpay-mainnet` namespace)

```bash
kubectl -n btcpay-mainnet create secret generic btcpay-secrets \
  --from-literal=BTCPAY_POSTGRES_PASSWORD='...' \
  # ... other BTCPay keys
```

`deploy/k8s/btcpay-mainnet/kustomization.yaml` deliberately omits `secrets.yaml`.

## Monitoring redis-exporter

Copy Redis password into the monitoring namespace (exporter cannot cross-namespace
`secretKeyRef`):

```bash
REDIS_PW=$(kubectl -n veritas get secret veritas-secrets -o jsonpath='{.data.REDIS_PASSWORD}' | base64 -d)
kubectl -n monitoring create secret generic redis-exporter-auth \
  --from-literal=REDIS_PASSWORD="$REDIS_PW" \
  --dry-run=client -o yaml | kubectl apply -f -
```

## Rule

Prefer `kubectl create secret` / `kubectl patch` / sealed-secrets. Never
`kubectl apply -f secrets.yaml` unless you have verified every key is present.
