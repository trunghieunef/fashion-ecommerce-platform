#!/usr/bin/env bash
# TASK:INV-01: database `inventory` with separate migration/runtime roles (05, ADR-03).
# Idempotent: runs at first init and again from scripts/local-up.sh for existing volumes.
set -euo pipefail

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=migration_password="$INVENTORY_MIGRATION_DB_PASSWORD" \
  --set=runtime_password="$INVENTORY_DB_PASSWORD" <<'SQL'
SELECT 'CREATE DATABASE inventory' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'inventory')\gexec
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'inventory_migration') THEN
    CREATE ROLE inventory_migration LOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'inventory_runtime') THEN
    CREATE ROLE inventory_runtime LOGIN;
  END IF;
END
$$;
ALTER ROLE inventory_migration PASSWORD :'migration_password';
ALTER ROLE inventory_runtime PASSWORD :'runtime_password';
-- Only this service's roles may connect: no cross-database access.
REVOKE CONNECT ON DATABASE inventory FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE inventory TO inventory_migration;
GRANT CONNECT ON DATABASE inventory TO inventory_runtime;
SQL

psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname inventory <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO inventory_migration;
GRANT USAGE ON SCHEMA public TO inventory_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE inventory_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO inventory_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE inventory_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO inventory_runtime;
SQL
