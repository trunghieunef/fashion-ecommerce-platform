CREATE ROLE catalog_migration LOGIN PASSWORD 'catalog_migration_test';
CREATE ROLE catalog_runtime LOGIN PASSWORD 'catalog_runtime_test';
GRANT CREATE ON DATABASE catalog TO catalog_migration;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO catalog_migration;
GRANT USAGE ON SCHEMA public TO catalog_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE catalog_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO catalog_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE catalog_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO catalog_runtime;
