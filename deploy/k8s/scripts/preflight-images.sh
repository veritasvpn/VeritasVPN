#!/usr/bin/env bash
set -euo pipefail

CLUSTER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OVERLAY="${1:-k3s}"
REGISTRY_HTTP_BASE="${REGISTRY_HTTP_BASE:-http://127.0.0.1:31500}"
RENDERED="$(mktemp)"
trap 'rm -f "$RENDERED"' EXIT

case "$OVERLAY" in
  k3s|prod|dev) ;;
  *) printf 'unsupported overlay: %s\n' "$OVERLAY" >&2; exit 2 ;;
esac

kubectl kustomize "$CLUSTER_DIR/overlays/$OVERLAY" >"$RENDERED"

mapfile -t images < <(awk '$1 == "image:" { print $2 }' "$RENDERED" | sort -u)
if (( ${#images[@]} == 0 )); then
  printf 'no images found in rendered overlay\n' >&2
  exit 1
fi

accept='application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'
checked=0
for image in "${images[@]}"; do
  case "$image" in
    localhost:31500/*)
      if [[ "$image" != *@sha256:* ]]; then
        printf '[FAIL] local production image is not digest-pinned: %s\n' "$image" >&2
        exit 1
      fi
      repository_and_digest="${image#localhost:31500/}"
      repository="${repository_and_digest%@*}"
      digest="${repository_and_digest#*@}"
      if curl --fail --silent --show-error --head \
        -H "Accept: $accept" \
        "$REGISTRY_HTTP_BASE/v2/$repository/manifests/$digest" >/dev/null; then
        printf '[OK]   registry contains %s@%s\n' "$repository" "$digest"
        checked=$((checked + 1))
      else
        printf '[FAIL] registry is missing %s@%s\n' "$repository" "$digest" >&2
        exit 1
      fi
      ;;
  esac
done

if (( checked == 0 )); then
  printf '[FAIL] no local production images were validated\n' >&2
  exit 1
fi
printf 'Image preflight: PASS (%d local images)\n' "$checked"
