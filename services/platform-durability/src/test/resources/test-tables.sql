-- Stand-ins for a service's business tables; not part of the durability DDL.
CREATE TABLE test_effects (event_id uuid NOT NULL, note text NOT NULL);
CREATE TABLE test_work (
  id uuid PRIMARY KEY,
  result text,
  lease_token uuid,
  lease_until timestamptz
);
