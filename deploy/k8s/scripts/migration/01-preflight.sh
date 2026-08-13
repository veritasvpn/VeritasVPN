#!/usr/bin/env bash
set -euo pipefail

echo "=== VeritasVPN: Docker Compose → k3s Migration ==="
echo ""

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"

check_root() {
  if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: must run as root (sudo)"
    exit 1
  fi
}

step() { echo ""; echo "=== STEP $1: $2 ==="; echo ""; }
confirm() { read -rp "$1 [y/N] " yn; if [ "$yn" != "y" ] && [ "$yn" != "Y" ]; then echo "aborted"; exit 0; fi; }

check_compose_running() {
  if ! docker compose ps --format json 2>/dev/null | grep -q running; then
    echo "ERROR: no running compose containers found. Start the stack first."
    exit 1
  fi
}

############################################################################
# STEP 1 — Pre-flight checks
############################################################################
step 1 "Pre-flight checks"

echo "Compose containers:"
docker compose ps --format 'table {{.Name}}\t{{.Image}}\t{{.Status}}' 2>/dev/null

echo ""
echo "WireGuard status:"
wg show 2>/dev/null || echo "  wg0 not found"
echo ""

check_root
check_compose_running

echo "Checking ports..."
free_ports() {
  for port in 6443 10250 2379 2380; do
    if ss -lnt | grep -q ":$port "; then
      echo "  WARNING: port $port is in use"
    else
      echo "  port $port: free"
    fi
  done
}
free_ports

confirm "Continue with migration?"

echo "[pre-flight] OK"
