#!/usr/bin/env bash
set -euo pipefail

echo "=== STEP 2: Rotate leaked secrets ==="
echo ""

REPO_ROOT="${REPO_ROOT:-/opt/veritasvpn}"

if [ ! -f "$REPO_ROOT/.env" ]; then
  echo "ERROR: .env not found at $REPO_ROOT/.env"
  exit 1
fi

echo "The following secrets must be rotated if they were committed:"
echo "  - DB_PASSWORD"
echo "  - JWT_ED25519_PRIVATE_KEY / JWT_ED25519_PUBLIC_KEYS / JWT_ACTIVE_KEY_ID"
echo "  - AGENT_AUTH_TOKEN"
echo ""
echo "Prefer: ./deploy/k8s/scripts/generate-jwt-ed25519.sh and openssl rand for other secrets,"
echo "then ./deploy/k8s/scripts/generate-secrets.sh and a controlled cluster secret patch."
echo "This interactive compose rotator is legacy; use the Ed25519 helpers above for k3s."
exit 1
