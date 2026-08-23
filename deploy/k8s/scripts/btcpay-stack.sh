#!/usr/bin/env bash
# Activate or deactivate a BTCPay stack (testnet or mainnet) without deleting PVCs.
# Only one stack should be active at a time in production; billing-svc must point
# at the active stack's btcpayserver Service (see overlays/prod ConfigMap patches).
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: btcpay-stack.sh <testnet|mainnet> <up|down|status>

  testnet  — btcpay namespace (Bitcoin testnet)
  mainnet  — btcpay-mainnet namespace (Bitcoin mainnet)

  up       — start postgres first, then remaining workloads
  down     — stop workloads; persistent volumes are kept for later catch-up sync
  status   — show pod and PVC state for the selected stack

Examples:
  btcpay-stack.sh testnet status
  btcpay-stack.sh mainnet down
  btcpay-stack.sh mainnet up
EOF
}

if [[ $# -ne 2 ]]; then
  usage
  exit 1
fi

STACK="$1"
ACTION="$2"

case "$STACK" in
  testnet)
    NS=btcpay
    POSTGRES=postgres-btcpay
    STATEFULSETS=(bitcoind nbxplorer)
    DEPLOYS=(btcpayserver bitcoin-readiness)
    ;;
  mainnet)
    NS=btcpay-mainnet
    POSTGRES=postgres-btcpay-mainnet
    STATEFULSETS=(bitcoind-mainnet nbxplorer-mainnet)
    DEPLOYS=(btcpayserver-mainnet bitcoin-readiness-mainnet)
    ;;
  *)
    echo "unknown stack: $STACK" >&2
    usage
    exit 1
    ;;
esac

kubectl_ns() {
  kubectl -n "$NS" "$@"
}

status() {
  echo "=== pods ($NS) ==="
  kubectl_ns get pods -o wide 2>/dev/null || echo "(namespace empty or missing)"
  echo ""
  echo "=== pvcs ($NS) ==="
  kubectl_ns get pvc 2>/dev/null || true
}

scale_down() {
  echo "Scaling down $STACK stack in $NS (PVCs retained)..."
  kubectl_ns scale "sts/$POSTGRES" --replicas=0
  kubectl_ns scale deploy/"${DEPLOYS[@]}" sts/"${STATEFULSETS[@]}" --replicas=0
  echo "Done. Reactivate with: $0 $STACK up"
}

wait_postgres() {
  echo "Waiting for postgres in $NS..."
  kubectl_ns wait --for=condition=ready "pod/${POSTGRES}-0" --timeout=180s
}

scale_up() {
  echo "Scaling up $STACK stack in $NS..."
  kubectl_ns scale "sts/$POSTGRES" --replicas=1
  wait_postgres
  kubectl_ns scale sts/"${STATEFULSETS[@]}" deploy/"${DEPLOYS[@]}" --replicas=1
  echo "Waiting for bitcoind..."
  kubectl_ns wait --for=condition=ready "pod/${STATEFULSETS[0]}-0" --timeout=600s || true
  echo "Done. bitcoind will catch up incrementally if the stack was previously stopped."
}

case "$ACTION" in
  status) status ;;
  down)   scale_down ;;
  up)     scale_up ;;
  *)
    echo "unknown action: $ACTION" >&2
    usage
    exit 1
    ;;
esac
