-- TASK:CAT-02b: inbox, durable work and read projections fed by inventory/order events (spec sections 3-5).
-- processed_events and background_tasks copy platform-durability/db/platform/durability.sql.
CREATE TABLE processed_events (
  consumer_name text NOT NULL,
  event_id uuid NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE background_tasks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  kind text NOT NULL,
  business_key text NOT NULL,
  payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  status varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'MANUAL')),
  attempts int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  lease_until timestamptz,
  lease_token uuid,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (kind, business_key),
  CHECK ((status = 'RUNNING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX background_tasks_due_idx ON background_tasks (status, next_attempt_at);

-- Display-only stock from INVENTORY_UPDATED/RESTOCKED; a stale stock_version never overwrites (CAT-09).
CREATE TABLE variant_stock_observations (
  sku varchar(64) PRIMARY KEY,
  available int NOT NULL CHECK (available >= 0),
  stock_version bigint NOT NULL CHECK (stock_version >= 1),
  observed_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- ORDER_COMPLETED applied once per order even when redelivered with another event_id.
CREATE TABLE product_sales_applied (
  order_id uuid PRIMARY KEY,
  applied_at timestamptz NOT NULL DEFAULT now()
);
REVOKE UPDATE, DELETE, TRUNCATE ON product_sales_applied FROM catalog_runtime;
