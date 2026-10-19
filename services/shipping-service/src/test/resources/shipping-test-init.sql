CREATE ROLE shipping_migration LOGIN PASSWORD 'shipping_migration_test';
CREATE ROLE shipping_runtime LOGIN PASSWORD 'shipping_runtime_test';
GRANT CREATE ON DATABASE shipping TO shipping_migration;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO shipping_migration;
GRANT USAGE ON SCHEMA public TO shipping_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE shipping_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO shipping_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE shipping_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO shipping_runtime;
