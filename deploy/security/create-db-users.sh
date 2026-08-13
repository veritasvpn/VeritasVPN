#!/usr/bin/env bash
set -euo pipefail

echo "[db-users] creating dedicated database users"
echo "  this script must be run with the current DB_PASSWORD set"

docker compose exec -T postgres psql -U veritas <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'auth_svc') THEN
    CREATE ROLE auth_svc WITH LOGIN PASSWORD current_setting('app.db_password_auth');
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'wg_manager') THEN
    CREATE ROLE wg_manager WITH LOGIN PASSWORD current_setting('app.db_password_wg');
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'billing_svc') THEN
    CREATE ROLE billing_svc WITH LOGIN PASSWORD current_setting('app.db_password_billing');
  END IF;
END
$$;

GRANT CONNECT ON DATABASE veritas TO auth_svc, wg_manager, billing_svc;

GRANT USAGE ON SCHEMA public TO auth_svc, wg_manager, billing_svc;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO auth_svc, wg_manager, billing_svc;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO auth_svc, wg_manager, billing_svc;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO auth_svc, wg_manager, billing_svc;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE ON SEQUENCES TO auth_svc, wg_manager, billing_svc;

ALTER ROLE veritas NOLOGIN;
SQL

echo "[db-users] done. Update DATABASE_URL in each service to use the new user."
echo "  auth-svc: postgres://auth_svc:PASSWORD@postgres:5432/veritas?sslmode=disable"
echo "  wg-manager: postgres://wg_manager:PASSWORD@postgres:5432/veritas?sslmode=disable"
echo "  billing-svc: postgres://billing_svc:PASSWORD@postgres:5432/veritas?sslmode=disable"
