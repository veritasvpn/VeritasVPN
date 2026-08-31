#!/usr/bin/env bash
# Remove JWT_SECRET from veritas-secrets only after the EdDSA cutover drain window.
# Safe to run hourly: no-ops until due, no-ops if already removed, refuses if any
# workload still mounts JWT_SECRET.
set -euo pipefail

NAMESPACE="${NAMESPACE:-veritas}"
SECRET_NAME="${SECRET_NAME:-veritas-secrets}"
CUTOVER_FILE="${JWT_CUTOVER_AT_FILE:-/etc/veritasvpn/jwt-cutover-at}"
# Default matches live Dell cutover (UTC).
DEFAULT_CUTOVER="2026-08-31T18:42:00Z"
DRAIN_SECONDS="${JWT_DRAIN_SECONDS:-86400}"
KUBECONFIG="${KUBECONFIG:-/home/jpg/.kube/config}"
export KUBECONFIG

force_if_due=0
dry_run=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --force-if-due) force_if_due=1; shift ;;
    --dry-run) dry_run=1; shift ;;
    -h|--help)
      printf 'Usage: %s [--force-if-due] [--dry-run]\n' "$0"
      exit 0
      ;;
    *) printf 'unknown arg: %s\n' "$1" >&2; exit 2 ;;
  esac
done

if ! command -v kubectl >/dev/null; then
  printf 'kubectl required\n' >&2
  exit 2
fi

cutover_raw=""
if [[ -f "$CUTOVER_FILE" ]]; then
  cutover_raw="$(tr -d '[:space:]' <"$CUTOVER_FILE")"
fi
if [[ -z "$cutover_raw" ]]; then
  cutover_raw="$DEFAULT_CUTOVER"
fi

cutover_epoch="$(date -u -d "$cutover_raw" +%s 2>/dev/null || true)"
if [[ -z "$cutover_epoch" ]]; then
  printf 'could not parse cutover time %q\n' "$cutover_raw" >&2
  exit 2
fi
due_epoch=$((cutover_epoch + DRAIN_SECONDS))
now_epoch="$(date -u +%s)"

if (( now_epoch < due_epoch )); then
  remain=$((due_epoch - now_epoch))
  printf 'JWT_SECRET drain window still open (%ss remaining; due %s)\n' \
    "$remain" "$(date -u -d "@$due_epoch" +%Y-%m-%dT%H:%M:%SZ)"
  exit 0
fi

# Refuse if any pod template still mounts JWT_SECRET.
mounts="$(kubectl -n "$NAMESPACE" get deploy,ds,sts -o json | python3 -c '
import json,sys
d=json.load(sys.stdin)
found=[]
for item in d.get("items",[]):
  kind=item.get("kind","")
  name=item["metadata"]["name"]
  spec=item.get("spec",{}).get("template",{}).get("spec",{})
  for c in spec.get("containers",[]) + spec.get("initContainers",[]):
    for e in c.get("env",[]) or []:
      if e.get("name")=="JWT_SECRET":
        found.append(f"{kind}/{name}")
print("\n".join(found))
')"
if [[ -n "$mounts" ]]; then
  printf 'refusing delete: workloads still reference JWT_SECRET:\n%s\n' "$mounts" >&2
  exit 1
fi

has_key="$(kubectl -n "$NAMESPACE" get secret "$SECRET_NAME" -o json \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("yes" if "JWT_SECRET" in (d.get("data") or {}) else "no")')"

if [[ "$has_key" != "yes" ]]; then
  printf 'JWT_SECRET already absent from %s/%s\n' "$NAMESPACE" "$SECRET_NAME"
  exit 0
fi

if (( dry_run == 1 )); then
  printf 'dry-run: would remove JWT_SECRET from %s/%s\n' "$NAMESPACE" "$SECRET_NAME"
  exit 0
fi

kubectl -n "$NAMESPACE" patch secret "$SECRET_NAME" --type=json \
  -p='[{"op":"remove","path":"/data/JWT_SECRET"}]'
printf 'Removed JWT_SECRET from %s/%s (cutover %s, drain %ss)\n' \
  "$NAMESPACE" "$SECRET_NAME" "$cutover_raw" "$DRAIN_SECONDS"
