#!/usr/bin/env bash
# TASK:ORD-01: database `orders` with separate migration/runtime roles (05, ADR-03).
# Idempotent: runs at first init and again from scripts/local-up.sh for existing volumes.
set -euo pipefail

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=migration_password="$ORDER_MIGRATION_DB_PASSWORD" \
  --set=runtime_password="$ORDER_DB_PASSWORD" <<'SQL'
SELECT 'CREATE DATABASE orders' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'orders')\gexec
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'order_migration') THEN
    CREATE ROLE order_migration LOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'order_runtime') THEN
    CREATE ROLE order_runtime LOGIN;
  END IF;
END
$$;
ALTER ROLE order_migration PASSWORD :'migration_password';
ALTER ROLE order_runtime PASSWORD :'runtime_password';
-- Only this service's roles may connect: no cross-database access.
REVOKE CONNECT ON DATABASE orders FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE orders TO order_migration;
GRANT CONNECT ON DATABASE orders TO order_runtime;
SQL

psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname orders <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO order_migration;
GRANT USAGE ON SCHEMA public TO order_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE order_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO order_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE order_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO order_runtime;
SQL
