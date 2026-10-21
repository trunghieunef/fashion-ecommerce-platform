-- TASK:ORD-03: OPS audit, API idempotency, shipping timestamps (05; spec section 3).
-- audit_logs copies catalog V002; idempotency_requests copies platform-durability/db/platform/durability.sql.

CREATE TABLE audit_logs (
  id uuid PRIMARY KEY,
  actor_id uuid,
  action text NOT NULL,
  resource_type text NOT NULL,
  resource_id text NOT NULL,
  reason text,
  before_data jsonb,
  after_data jsonb,
  request_id uuid NOT NULL,
  source_ip inet,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_logs_resource_idx ON audit_logs (resource_type, resource_id, created_at);
REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs FROM order_runtime;

CREATE TABLE idempotency_requests (
  actor_key text NOT NULL,
  operation text NOT NULL,
  key varchar(128) NOT NULL,
  request_hash char(64) NOT NULL,
  resource_id uuid,
  status varchar(32) NOT NULL DEFAULT 'PROCESSING' CHECK (status IN ('PROCESSING', 'COMPLETED')),
  response_code int,
  response_body jsonb,
  expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (actor_key, operation, key),
  CHECK ((status = 'COMPLETED') = (response_code IS NOT NULL))
);

ALTER TABLE orders ADD COLUMN shipped_at timestamptz;
ALTER TABLE order_sagas ADD COLUMN shipment_version bigint;
