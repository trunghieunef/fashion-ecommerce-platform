#!/usr/bin/env bash
# TASK:NOT-01: database `notification` with separate migration/runtime roles (05, ADR-03).
# Idempotent: runs at first init and again from scripts/local-up.sh for existing volumes.
set -euo pipefail

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=migration_password="$NOTIFICATION_MIGRATION_DB_PASSWORD" \
  --set=runtime_password="$NOTIFICATION_DB_PASSWORD" <<'SQL'
SELECT 'CREATE DATABASE notification' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'notification')\gexec
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'notification_migration') THEN
    CREATE ROLE notification_migration LOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'notification_runtime') THEN
    CREATE ROLE notification_runtime LOGIN;
  END IF;
END
$$;
ALTER ROLE notification_migration PASSWORD :'migration_password';
ALTER ROLE notification_runtime PASSWORD :'runtime_password';
-- Only this service's roles may connect: no cross-database access.
REVOKE CONNECT ON DATABASE notification FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE notification TO notification_migration;
GRANT CONNECT ON DATABASE notification TO notification_runtime;
SQL

psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname notification <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO notification_migration;
GRANT USAGE ON SCHEMA public TO notification_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE notification_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO notification_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE notification_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO notification_runtime;
SQL
