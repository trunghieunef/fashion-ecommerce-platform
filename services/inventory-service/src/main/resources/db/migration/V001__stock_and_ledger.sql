-- TASK:INV-01: inventory authority and append-only ledger.
CREATE TABLE stock_items (
  sku varchar(64) PRIMARY KEY,
  product_id uuid NOT NULL,
  variant_id uuid NOT NULL UNIQUE,
  on_hand int NOT NULL DEFAULT 0,
  reserved int NOT NULL DEFAULT 0,
  min_stock int NOT NULL DEFAULT 0 CHECK (min_stock >= 0),
  status varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (reserved >= 0 AND reserved <= on_hand)
);
CREATE TABLE stock_transactions (
  id uuid PRIMARY KEY,
  sku varchar(64) NOT NULL REFERENCES stock_items(sku),
  operation_key text NOT NULL,
  type varchar(16) NOT NULL CHECK (type IN ('INBOUND','RESERVE','COMMIT','RELEASE','ADJUST','RETURN')),
  on_hand_delta int NOT NULL,
  reserved_delta int NOT NULL,
  order_id uuid,
  reason text NOT NULL,
  actor_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (operation_key,sku)
);
CREATE INDEX stock_transactions_sku_created_id_idx ON stock_transactions(sku,created_at,id);
REVOKE UPDATE, DELETE, TRUNCATE ON stock_transactions FROM inventory_runtime;
GRANT SELECT, INSERT ON stock_transactions TO inventory_runtime;
REVOKE DELETE, TRUNCATE ON stock_items FROM inventory_runtime;
GRANT SELECT, INSERT, UPDATE ON stock_items TO inventory_runtime;

-- TASK:PLT-03 durability tables (05 section 3). Each service copies this DDL into its OWN
-- Flyway migration (next free version) in its own database; this file is not a migration and
-- is never applied to a shared database. Deployed migrations stay append-only.

CREATE TABLE outbox_events (
  id uuid PRIMARY KEY,
  aggregate_type text NOT NULL,
  aggregate_id text NOT NULL,
  aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
  aggregate_sequence bigint NOT NULL CHECK (aggregate_sequence >= 1),
  event_type text NOT NULL,
  schema_version int NOT NULL DEFAULT 1 CHECK (schema_version >= 1),
  topic text NOT NULL,
  partition_key text NOT NULL,
  correlation_id text NOT NULL,
  payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  status varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SENT')),
  attempts int NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  lease_until timestamptz,
  lease_token uuid,
  published_at timestamptz,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (aggregate_type, aggregate_id, aggregate_sequence),
  CHECK ((status = 'IN_FLIGHT') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
  CHECK ((status = 'SENT') = (published_at IS NOT NULL))
);

CREATE INDEX outbox_events_unsent_idx
  ON outbox_events (next_attempt_at, created_at)
  WHERE status <> 'SENT';

CREATE TABLE processed_events (
  consumer_name text NOT NULL,
  event_id uuid NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer_name, event_id)
);

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
