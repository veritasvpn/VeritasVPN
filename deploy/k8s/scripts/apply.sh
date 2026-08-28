#!/usr/bin/env bash
set -euo pipefail

CLUSTER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OVERLAY="${1:-k3s}"
STATE_ROOT="${VERITAS_DEPLOY_STATE_ROOT:-${XDG_STATE_HOME:-$HOME/.local/state}/veritasvpn/deployments}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
SNAPSHOT_DIR="$STATE_ROOT/$STAMP"
SNAPSHOT="$SNAPSHOT_DIR/veritas-before.json"
ROLLED_BACK=0

case "$OVERLAY" in
  base|"")
    printf 'Refusing to apply base alone; use k3s or dev.\n' >&2
    exit 2
    ;;
  prod)
    printf 'overlays/prod is a legacy alias; validating it before deployment.\n'
    ;;
  k3s|dev) ;;
  *) printf 'Unknown overlay %s. Use k3s, dev, or prod.\n' "$OVERLAY" >&2; exit 2 ;;
esac

mkdir -p "$SNAPSHOT_DIR"
chmod 700 "$STATE_ROOT" "$SNAPSHOT_DIR"

rollback() {
  status=$?
  trap - ERR INT TERM
  if (( ROLLED_BACK == 0 )); then
    ROLLED_BACK=1
    printf '\nDeployment failed; restoring %s\n' "$SNAPSHOT" >&2
    kubectl apply --server-side --force-conflicts -f "$SNAPSHOT" >&2 || true
    for daemonset in veritas-agent veritas-wstunnel; do
      desired="$(kubectl -n veritas get daemonset "$daemonset" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)"
      running="$(kubectl -n veritas get pods -l "app=$daemonset" -o json 2>/dev/null \
        | jq -r '.items[] | select(.metadata.deletionTimestamp == null) | .spec.containers[0].image' \
        | head -1)"
      if [[ -n "$desired" && "$running" != "$desired" ]]; then
        kubectl -n veritas delete pod -l "app=$daemonset" --wait=false >&2 || true
      fi
    done
    "$CLUSTER_DIR/scripts/verify-core.sh" >&2 || true
    printf 'Rollback attempt completed. Snapshot retained at %s\n' "$SNAPSHOT" >&2
  fi
  exit "$status"
}

"$CLUSTER_DIR/scripts/preflight-images.sh" "$OVERLAY"

kubectl -n veritas get \
  deployment,daemonset,statefulset,service,configmap,networkpolicy,poddisruptionbudget,ingress \
  -o json | jq '
    .items |= map(select(.metadata.name != "kube-root-ca.crt")) |
    del(
      .items[].metadata.creationTimestamp,
      .items[].metadata.generation,
      .items[].metadata.managedFields,
      .items[].metadata.resourceVersion,
      .items[].metadata.uid,
      .items[].status,
      .items[].metadata.annotations."deployment.kubernetes.io/revision",
      .items[].metadata.annotations."deprecated.daemonset.template.generation",
      .items[].metadata.annotations."kubectl.kubernetes.io/last-applied-configuration"
    )' >"$SNAPSHOT"
chmod 600 "$SNAPSHOT"
git -C "$CLUSTER_DIR/../.." rev-parse HEAD >"$SNAPSHOT_DIR/source-commit.txt" 2>/dev/null || true
kubectl get nodes -o wide >"$SNAPSHOT_DIR/nodes.txt"
kubectl -n veritas get pods -o wide >"$SNAPSHOT_DIR/pods-before.txt"
trap rollback ERR INT TERM

printf '\nPlanned Kubernetes changes:\n'
if kubectl diff --server-side --force-conflicts -k "$CLUSTER_DIR/overlays/$OVERLAY" >"$SNAPSHOT_DIR/diff.txt"; then
  diff_status=0
else
  diff_status=$?
fi
if [[ "${diff_status:-0}" != "0" && "${diff_status:-0}" != "1" ]]; then
  printf 'kubectl diff failed with status %s\n' "$diff_status" >&2
  exit "$diff_status"
fi
changed_resources="$(grep -c '^--- ' "$SNAPSHOT_DIR/diff.txt" || true)"
printf '%s resource(s) differ; full diff saved to %s\n' "$changed_resources" "$SNAPSHOT_DIR/diff.txt"

printf '\nApplying VeritasVPN overlay: %s\n' "$OVERLAY"
kubectl apply --server-side --force-conflicts -k "$CLUSTER_DIR/overlays/$OVERLAY"

# These host-network daemonsets deliberately use OnDelete. Rotate only if the
# running image differs from the desired digest; any failure invokes rollback.
for daemonset in veritas-agent veritas-wstunnel; do
  desired="$(kubectl -n veritas get daemonset "$daemonset" -o jsonpath='{.spec.template.spec.containers[0].image}')"
  running="$(kubectl -n veritas get pods -l "app=$daemonset" -o json \
    | jq -r '.items[] | select(.metadata.deletionTimestamp == null) | .spec.containers[0].image' \
    | head -1)"
  if [[ "$running" != "$desired" ]]; then
    printf 'Replacing %s: %s -> %s\n' "$daemonset" "${running:-none}" "$desired"
    kubectl -n veritas delete pod -l "app=$daemonset" --wait=true
    kubectl -n veritas wait --for=condition=Ready pod -l "app=$daemonset" --timeout=180s
  fi
done

"$CLUSTER_DIR/scripts/verify-core.sh"
kubectl -n veritas get pods -o wide >"$SNAPSHOT_DIR/pods-after.txt"
date -u +%FT%TZ >"$SNAPSHOT_DIR/success.txt"

trap - ERR INT TERM
printf '\nDeployment succeeded. Rollback snapshot: %s\n' "$SNAPSHOT_DIR"
