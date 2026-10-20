#!/usr/bin/env bash
# TASK:PAY-01: database `payment` with separate migration/runtime roles (05, ADR-03).
# Idempotent: runs at first init and again from scripts/local-up.sh for existing volumes.
set -euo pipefail

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=migration_password="$PAYMENT_MIGRATION_DB_PASSWORD" \
  --set=runtime_password="$PAYMENT_DB_PASSWORD" <<'SQL'
SELECT 'CREATE DATABASE payment' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'payment')\gexec
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payment_migration') THEN
    CREATE ROLE payment_migration LOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'payment_runtime') THEN
    CREATE ROLE payment_runtime LOGIN;
  END IF;
END
$$;
ALTER ROLE payment_migration PASSWORD :'migration_password';
ALTER ROLE payment_runtime PASSWORD :'runtime_password';
-- Only this service's roles may connect: no cross-database access.
REVOKE CONNECT ON DATABASE payment FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE payment TO payment_migration;
GRANT CONNECT ON DATABASE payment TO payment_runtime;
SQL

psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname payment <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO payment_migration;
GRANT USAGE ON SCHEMA public TO payment_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE payment_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO payment_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE payment_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO payment_runtime;
SQL
