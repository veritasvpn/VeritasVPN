#!/usr/bin/env bash
set -euo pipefail

echo "=== STEP 6: Migrate PostgreSQL data ==="
echo ""

REPO_ROOT="${REPO_ROOT:-/opt/veritasvpn}"
confirm() { read -rp "$1 [y/N] " yn; if [ "$yn" != "y" ] && [ "$yn" != "Y" ]; then echo "aborted"; exit 0; fi; }

confirm "This will STOP the compose postgres, dump data, and restore into k3s postgres. Continue?"

cd "$REPO_ROOT"

echo "Creating dump from compose postgres..."
docker compose exec -T postgres pg_dumpall -U veritas | gzip > /tmp/veritas-migration.sql.gz
echo "  dump size: $(du -h /tmp/veritas-migration.sql.gz | cut -f1)"

echo "Stopping compose postgres to free port 5432..."
docker compose stop postgres

echo "Deploying only k3s postgres..."
kubectl apply -k "$REPO_ROOT/deploy/k8s/overlays/prod/" 2>/dev/null || true
kubectl -n veritas delete deploy auth-svc wg-manager billing-svc nginx veritas-proxy redis --ignore-not-found
kubectl -n veritas delete sts nats --ignore-not-found
kubectl -n veritas delete ds veritas-agent --ignore-not-found

echo "Waiting for k3s postgres to be ready..."
kubectl -n veritas wait --for=condition=ready pod -l app=postgres --timeout=120s

echo "Restoring data into k3s postgres..."
POSTGRES_POD=$(kubectl -n veritas get pod -l app=postgres -o jsonpath='{.items[0].metadata.name}')
gunzip -c /tmp/veritas-migration.sql.gz | kubectl -n veritas exec -i "$POSTGRES_POD" -- psql -U veritas

echo "Verifying table count..."
kubectl -n veritas exec "$POSTGRES_POD" -- psql -U veritas -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';"

echo ""
echo "[postgres-migration] Done. Data is in k3s:"
kubectl -n veritas get pods -l app=postgres
