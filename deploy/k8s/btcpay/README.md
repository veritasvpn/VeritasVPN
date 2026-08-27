# BTCPay testnet stack (deprecated)

**Status:** Retired for production. Mainnet lives in [`../btcpay-mainnet/`](../btcpay-mainnet/).

The `btcpay` namespace on Dell is scaled to **0** (deployments/statefulsets at 0/0). Do **not** apply this kustomization in production without an explicit restore plan.

## Before deleting the namespace

1. Confirm a recent backup archive contains `btcpay.sql.gz` and `btcpay-k8s.yaml` (`deploy/systemd/veritas-backup-verify.sh`).
2. Document PVC names/sizes: `kubectl -n btcpay get pvc`.
3. Only then: `kubectl delete namespace btcpay` (human confirmation required).

Health and readiness scripts treat `btcpay` as optional legacy; they gate on `btcpay-mainnet` only.
