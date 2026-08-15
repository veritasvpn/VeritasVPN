#!/usr/bin/env bash
set -euo pipefail

REGISTRY="${REGISTRY:-localhost:31500}"
IMAGE_PREFIX="${IMAGE_PREFIX:-}"
TAG="${TAG:?TAG must be set to an immutable version or digest}"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"

services=("auth-svc" "wg-manager" "billing-svc" "veritas-agent" "veritas-proxy")
dockerfiles=(
  "services/auth-svc/Dockerfile"
  "services/wg-manager/Dockerfile"
  "services/billing-svc/Dockerfile"
  "services/veritas-agent/Dockerfile"
  "containers/proxy-gateway/Dockerfile"
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
  ctx="${ROOT}"
  if [ "$svc" = "veritas-proxy" ]; then
    ctx="${ROOT}/containers/proxy-gateway"
  fi
  echo "--- Building ${img} ---"
  docker build -t "${img}" -f "${ROOT}/${df}" "${ctx}"
  docker push "${img}"
done

echo ""
echo "All images pushed to ${REGISTRY}"
