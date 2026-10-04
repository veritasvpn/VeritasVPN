#!/usr/bin/env bash
# Scoped rollout: deliberately does not apply the whole overlay, which may have
# unrelated site-local billing/config changes. Run with sudo on the Dell.
set -euo pipefail
cd "$(dirname "$0")/../.."
declare -A images=(
 [auth-svc]='localhost:31500/auth-svc@sha256:6ce1039fd853ef8c81e3bfcb2c46e9d64e1f85df6c0da8734e3ea9afedaa0dba'
 [veritas-proxy]='localhost:31500/veritas-proxy@sha256:33e7a3e011d899e78b4790da37f5a13144a9087d95fd2b5d4d616f9c9d4b0746'
 [phishing-checker]='localhost:31500/phishing-checker@sha256:fd6cb81e34d1936b05d542f5aa4ac76d72c7d831603c0007e7a71a18769fecac'
 [veritas-agent]='localhost:31500/veritas-agent@sha256:d4e18eb1a2d05b6ee7b799003238b9cf86af92aa21e0a9b4899133c52a6576e7'
)
kubectl -n veritas get secret veritas-secrets -o json | jq -e '.data.TOOLS_RATE_LIMIT_SECRET | length > 0' >/dev/null
for image in "${images[@]}"; do
  ref=${image#localhost:31500/}
  curl -fsS --max-time 10 -H 'Accept: application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json' \
    "http://127.0.0.1:31500/v2/${ref%@*}/manifests/${ref#*@}" >/dev/null
done
umask 077
snapshot_dir=$(mktemp -d /var/tmp/veritas-security-rollout.XXXXXXXX)
kubectl -n veritas get deployment/auth-svc deployment/veritas-proxy deployment/phishing-checker daemonset/veritas-agent \
  networkpolicy/allow-egress-veritas-proxy networkpolicy/allow-egress-phishing-checker -o json | jq \
  'del(.items[].metadata.resourceVersion,.items[].metadata.uid,.items[].metadata.managedFields,.items[].status)' > "$snapshot_dir/before.json"
echo "Rollback snapshot: $snapshot_dir"
kubectl create --dry-run=client -f deploy/k8s/base/network-policy.yaml -o json | jq -s \
  '{apiVersion:"v1",kind:"List",items:[.[] | (if .kind == "List" then .items[] else . end) | select(.metadata.name == "allow-egress-veritas-proxy" or .metadata.name == "allow-egress-phishing-checker")]}' > "$snapshot_dir/policies.json"
jq -e '.items | length == 2' "$snapshot_dir/policies.json" >/dev/null
rollback() {
  trap - ERR INT TERM
  echo 'Verification failed; restoring the scoped snapshot.' >&2
  kubectl apply -f "$snapshot_dir/before.json"
  kubectl -n veritas delete pod -l app=veritas-agent --wait=true
  kubectl -n veritas wait --for=condition=Ready pod -l app=veritas-agent --timeout=120s || true
  bash deploy/k8s/scripts/verify-core.sh || true
  exit 1
}
trap rollback ERR INT TERM
kubectl -n veritas patch deployment auth-svc --type strategic -p \
  "{\"spec\":{\"template\":{\"spec\":{\"containers\":[{\"name\":\"auth-svc\",\"image\":\"${images[auth-svc]}\",\"env\":[{\"name\":\"TOOLS_RATE_LIMIT_SECRET\",\"valueFrom\":{\"secretKeyRef\":{\"name\":\"veritas-secrets\",\"key\":\"TOOLS_RATE_LIMIT_SECRET\"}}}]}]}}}}"
kubectl -n veritas rollout status deployment/auth-svc --timeout=120s
for service in veritas-proxy phishing-checker; do
  kubectl -n veritas set image "deployment/$service" "$service=${images[$service]}"
  kubectl -n veritas rollout status "deployment/$service" --timeout=120s
done
kubectl apply -f "$snapshot_dir/policies.json"
kubectl -n veritas set image daemonset/veritas-agent "veritas-agent=${images[veritas-agent]}"
kubectl -n veritas delete pod -l app=veritas-agent --wait=true
kubectl -n veritas wait --for=condition=Ready pod -l app=veritas-agent --timeout=120s
bash deploy/k8s/scripts/verify-core.sh
# Read the full output before matching: with pipefail, grep -q may close the
# pipe early and turn nft's SIGPIPE into a spurious verification failure.
nft list chain inet veritas forward > "$snapshot_dir/forward-after.txt"
grep -q 'iifname "wg0" oifname "cni0" counter.*drop' "$snapshot_dir/forward-after.txt"
trap - ERR INT TERM
echo 'Scoped security rollout and core verification passed.'
