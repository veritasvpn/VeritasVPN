#!/usr/bin/env bash
set -euo pipefail

# Default assumes: kubectl -n veritas port-forward svc/registry 31500:5000
# (loopback only). Do not reintroduce an unauthenticated registry NodePort.
REGISTRY="${REGISTRY:-localhost:31500}"
IMAGE_PREFIX="${IMAGE_PREFIX:-}"
TAG="${TAG:?TAG must be set to an immutable version or digest}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
BUILD_CONTEXT="$(mktemp -d)"
trap 'rm -rf "$BUILD_CONTEXT"' EXIT

# Docker cannot safely use an arbitrary live checkout as its context: local
# backups and credentials may be unreadable or accidentally included. Build
# exactly the tracked revision instead, which also makes release artifacts
# reproducible.
git -C "$ROOT" archive --format=tar HEAD | tar -x -C "$BUILD_CONTEXT"

if [[ "${REGISTRY}" == "localhost:31500" || "${REGISTRY}" == "127.0.0.1:31500" ]]; then
  if ! curl -fsS --connect-timeout 1 "http://${REGISTRY}/v2/" >/dev/null 2>&1; then
    echo "Registry ${REGISTRY} is not reachable." >&2
    echo "Start a loopback forward first:" >&2
    echo "  kubectl -n veritas port-forward svc/registry 31500:5000" >&2
    exit 1
  fi
fi

services=("auth-svc" "wg-manager" "billing-svc" "phishing-checker" "veritas-agent" "veritas-proxy" "wstunnel")
dockerfiles=(
  "services/auth-svc/Dockerfile"
  "services/wg-manager/Dockerfile"
  "services/billing-svc/Dockerfile"
  "services/phishing-checker/Dockerfile"
  "services/veritas-agent/Dockerfile"
  "services/browser-proxy/Dockerfile"
  "services/wstunnel/Dockerfile"
)

echo "Building images for: ${REGISTRY}"

for i in "${!services[@]}"; do
  svc="${services[$i]}"
  df="${dockerfiles[$i]}"
  if [ -n "${IMAGE_PREFIX}" ]; then
    img="${REGISTRY}/${IMAGE_PREFIX}/${svc}:${TAG}"
  else
    img="${REGISTRY}/${svc}:${TAG}"
  fi
  echo "--- Building ${img} ---"
  # The production host provides its working resolver through the host network.
  # Using it here avoids Docker daemon DNS overrides and affects build steps only.
  docker build --network=host -t "${img}" -f "${BUILD_CONTEXT}/${df}" "${BUILD_CONTEXT}"
  docker push "${img}"
done

echo ""
echo "All images pushed to ${REGISTRY}"
