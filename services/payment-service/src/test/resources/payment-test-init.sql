CREATE ROLE payment_migration LOGIN PASSWORD 'payment_migration_test';
CREATE ROLE payment_runtime LOGIN PASSWORD 'payment_runtime_test';
GRANT CREATE ON DATABASE payment TO payment_migration;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO payment_migration;
GRANT USAGE ON SCHEMA public TO payment_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE payment_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO payment_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE payment_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO payment_runtime;
