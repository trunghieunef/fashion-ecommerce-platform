#!/usr/bin/env bash
# TASK:USR-01a: database `users` with separate migration/runtime roles (05, ADR-03).
# Idempotent: runs at first init and again from scripts/local-up.sh for existing volumes.
set -euo pipefail

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=migration_password="$USER_MIGRATION_DB_PASSWORD" \
  --set=runtime_password="$USER_DB_PASSWORD" <<'SQL'
SELECT 'CREATE DATABASE users' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'users')\gexec
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'user_migration') THEN
    CREATE ROLE user_migration LOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'user_runtime') THEN
    CREATE ROLE user_runtime LOGIN;
  END IF;
END
$$;
ALTER ROLE user_migration PASSWORD :'migration_password';
ALTER ROLE user_runtime PASSWORD :'runtime_password';
-- Only this service's roles may connect: no cross-database access.
REVOKE CONNECT ON DATABASE users FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE users TO user_migration;
GRANT CONNECT ON DATABASE users TO user_runtime;
SQL

psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname users <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO user_migration;
GRANT USAGE ON SCHEMA public TO user_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE user_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO user_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE user_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO user_runtime;
SQL
