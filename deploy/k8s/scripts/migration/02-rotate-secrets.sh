#!/usr/bin/env bash
set -euo pipefail

echo "=== STEP 2: Rotate leaked secrets ==="
echo ""

REPO_ROOT="${REPO_ROOT:-/opt/veritasvpn}"

if [ ! -f "$REPO_ROOT/.env" ]; then
  echo "ERROR: .env not found at $REPO_ROOT/.env"
  exit 1
fi

source "$REPO_ROOT/.env"

echo "The following secrets were committed to Git and must be rotated:"
echo "  - DB_PASSWORD"
echo "  - JWT_SECRET"
echo "  - AGENT_AUTH_TOKEN"
echo ""
echo "The current compose stack will be restarted with new credentials."

read -rp "Continue? [y/N] " yn
if [ "$yn" != "y" ] && [ "$yn" != "Y" ]; then exit 0; fi

NEW_DB=$(openssl rand -hex 16)
NEW_JWT=$(openssl rand -hex 32)
NEW_AGENT=$(openssl rand -hex 24)

echo "Generating new secrets..."
echo "  DB_PASSWORD: ${NEW_DB:0:4}..."
echo "  JWT_SECRET: ${NEW_JWT:0:4}..."
echo "  AGENT_AUTH_TOKEN: ${NEW_AGENT:0:4}..."

cd "$REPO_ROOT"

echo "Updating .env..."
sed -i "s/^DB_PASSWORD=.*/DB_PASSWORD=$NEW_DB/" .env
sed -i "s/^JWT_SECRET=.*/JWT_SECRET=$NEW_JWT/" .env
sed -i "s/^AGENT_AUTH_TOKEN=.*/AGENT_AUTH_TOKEN=$NEW_AGENT/" .env

echo "Restarting compose stack with new secrets..."
docker compose down
docker compose up -d

echo "Waiting for services..."
sleep 15
docker compose ps

echo "Removing old secrets from Git tracking (if committed)..."
cd "$REPO_ROOT"
git log --oneline -- deploy/k8s/base/secrets.yaml 2>/dev/null | head -3 || true

echo ""
echo "[rotate-secrets] Done. If the old secrets were pushed to remote, force-push a cleaned history."
echo "  git filter-branch --force --index-filter"
echo "  git push --force --all"
echo ""
echo "Also update the k3s secrets file:"
echo "  cp $REPO_ROOT/deploy/k8s/base/secrets.example.yaml $REPO_ROOT/deploy/k8s/base/secrets.yaml"
echo "  Edit and fill in the new values"
