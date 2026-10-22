CREATE ROLE notification_migration LOGIN PASSWORD 'notification_migration_test';
CREATE ROLE notification_runtime LOGIN PASSWORD 'notification_runtime_test';
GRANT CREATE ON DATABASE notification TO notification_migration;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO notification_migration;
GRANT USAGE ON SCHEMA public TO notification_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE notification_migration IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO notification_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE notification_migration IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO notification_runtime;
