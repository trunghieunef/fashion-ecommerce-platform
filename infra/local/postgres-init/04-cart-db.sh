#!/usr/bin/env bash
# TASK:CART-01: database `cart` with separate migration/runtime roles (05, ADR-03).
# Idempotent: runs at first init and again from scripts/local-up.sh for existing volumes.
set -euo pipefail

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=migration_password="$CART_MIGRATION_DB_PASSWORD" \
  --set=runtime_password="$CART_DB_PASSWORD" <<'SQL'
SELECT 'CREATE DATABASE cart' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'cart')\gexec
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cart_migration') THEN
    CREATE ROLE cart_migration LOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cart_runtime') THEN
    CREATE ROLE cart_runtime LOGIN;
  END IF;
END
$$;
ALTER ROLE cart_migration PASSWORD :'migration_password';
ALTER ROLE cart_runtime PASSWORD :'runtime_password';
-- Only this service's roles may connect: no cross-database access.
REVOKE CONNECT ON DATABASE cart FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE cart TO cart_migration;
GRANT CONNECT ON DATABASE cart TO cart_runtime;
SQL

psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname cart <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO cart_migration;
GRANT USAGE ON SCHEMA public TO cart_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE cart_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cart_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE cart_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO cart_runtime;
SQL
