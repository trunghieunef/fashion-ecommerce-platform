#!/usr/bin/env bash
# TASK:SHP-01: database `shipping` with separate migration/runtime roles (05, ADR-03).
# Idempotent: runs at first init and again from scripts/local-up.sh for existing volumes.
set -euo pipefail

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=migration_password="$SHIPPING_MIGRATION_DB_PASSWORD" \
  --set=runtime_password="$SHIPPING_DB_PASSWORD" <<'SQL'
SELECT 'CREATE DATABASE shipping' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'shipping')\gexec
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shipping_migration') THEN
    CREATE ROLE shipping_migration LOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shipping_runtime') THEN
    CREATE ROLE shipping_runtime LOGIN;
  END IF;
END
$$;
ALTER ROLE shipping_migration PASSWORD :'migration_password';
ALTER ROLE shipping_runtime PASSWORD :'runtime_password';
-- Only this service's roles may connect: no cross-database access.
REVOKE CONNECT ON DATABASE shipping FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE shipping TO shipping_migration;
GRANT CONNECT ON DATABASE shipping TO shipping_runtime;
SQL

psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname shipping <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO shipping_migration;
GRANT USAGE ON SCHEMA public TO shipping_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE shipping_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO shipping_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE shipping_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO shipping_runtime;
SQL
