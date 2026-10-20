CREATE ROLE order_migration LOGIN PASSWORD 'order_migration_test';
CREATE ROLE order_runtime LOGIN PASSWORD 'order_runtime_test';
GRANT CREATE ON DATABASE orders TO order_migration;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO order_migration;
GRANT USAGE ON SCHEMA public TO order_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE order_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO order_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE order_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO order_runtime;
