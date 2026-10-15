CREATE ROLE inventory_migration LOGIN PASSWORD 'inventory_migration_test';
CREATE ROLE inventory_runtime LOGIN PASSWORD 'inventory_runtime_test';
GRANT CREATE ON DATABASE inventory TO inventory_migration;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO inventory_migration;
GRANT USAGE ON SCHEMA public TO inventory_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE inventory_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO inventory_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE inventory_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO inventory_runtime;
