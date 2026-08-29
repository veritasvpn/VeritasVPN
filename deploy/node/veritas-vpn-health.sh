#!/usr/bin/env bash
set -euo pipefail

# Compatibility entry point for older systemd units. The Docker Compose/Pi
# health check is retired; production runs only on Dell K3s.
REPO_ROOT="${REPO_ROOT:-/home/jpg/VeritasVPN}"
exec "$REPO_ROOT/deploy/systemd/veritas-k8s-health.sh"
