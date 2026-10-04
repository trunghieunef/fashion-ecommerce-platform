#!/usr/bin/env bash
set -euo pipefail

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=database_name="$POSTGRES_DB" \
  --set=migration_password="$CATALOG_MIGRATION_DB_PASSWORD" \
  --set=runtime_password="$CATALOG_DB_PASSWORD" <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'catalog_migration') THEN
    CREATE ROLE catalog_migration LOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'catalog_runtime') THEN
    CREATE ROLE catalog_runtime LOGIN;
  END IF;
END
$$;

ALTER ROLE catalog_migration PASSWORD :'migration_password';
ALTER ROLE catalog_runtime PASSWORD :'runtime_password';
GRANT CONNECT, CREATE ON DATABASE :"database_name" TO catalog_migration;
GRANT CONNECT ON DATABASE :"database_name" TO catalog_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO catalog_migration;
GRANT USAGE ON SCHEMA public TO catalog_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE catalog_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO catalog_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE catalog_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO catalog_runtime;
SQL
