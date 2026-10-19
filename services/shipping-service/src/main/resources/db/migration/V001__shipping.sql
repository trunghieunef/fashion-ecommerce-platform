-- TASK:SHP-01: SELF fee rules, quotes and shipments (05 section 11.1, SHP-01 spec section 3).
CREATE TABLE shipping_rules (
  id uuid PRIMARY KEY,
  name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  rule_type varchar(16) NOT NULL CHECK (rule_type IN ('FLAT', 'PROVINCE', 'WEIGHT')),
  priority int NOT NULL CHECK (priority >= 0),
  config_json jsonb NOT NULL CHECK (jsonb_typeof(config_json) = 'object'),
  active boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX shipping_rules_active_priority_idx ON shipping_rules (active, priority);

-- Append-only audit of every rule write (who, why, resulting config).
CREATE TABLE shipping_rule_changes (
  id uuid PRIMARY KEY,
  rule_id uuid NOT NULL REFERENCES shipping_rules(id),
  version bigint NOT NULL,
  actor_id uuid,
  reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
  snapshot jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (rule_id, version)
);

-- New against 05: the order snapshots quote_id; a shipment is created from exactly one quote.
CREATE TABLE shipping_quotes (
  id uuid PRIMARY KEY,
  input_hash char(64) NOT NULL,
  province_code varchar(32) NOT NULL,
  weight_grams int NOT NULL CHECK (weight_grams > 0),
  cod boolean NOT NULL,
  fee bigint NOT NULL CHECK (fee >= 0),
  cod_fee bigint NOT NULL CHECK (cod_fee >= 0),
  rule_id uuid NOT NULL REFERENCES shipping_rules(id),
  rule_version bigint NOT NULL,
  carrier varchar(16) NOT NULL DEFAULT 'SELF',
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE shipments (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL UNIQUE,
  order_no varchar(32) NOT NULL UNIQUE CHECK (length(order_no) >= 1),
  quote_id uuid NOT NULL UNIQUE REFERENCES shipping_quotes(id),
  carrier varchar(16) NOT NULL CHECK (carrier IN ('SELF')),
  provider_request_id text NOT NULL UNIQUE,
  tracking_code text,
  status varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','UNKNOWN','CREATED','PICKING','DELIVERING',
    'DELIVERED','FAILED','RETURNING','RETURNED','CANCEL_PENDING','CANCELLED','MANUAL')),
  fee bigint NOT NULL CHECK (fee >= 0),
  weight_grams int NOT NULL CHECK (weight_grams > 0),
  from_address jsonb NOT NULL,
  to_address jsonb NOT NULL,
  cod_amount bigint NOT NULL DEFAULT 0 CHECK (cod_amount >= 0),
  cod_collected_amount bigint NOT NULL DEFAULT 0 CHECK (cod_collected_amount >= 0),
  cod_remitted_amount bigint NOT NULL DEFAULT 0 CHECK (cod_remitted_amount >= 0),
  handed_over_at timestamptz,
  assigned_to uuid,
  request_hash char(64) NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (cod_remitted_amount <= cod_collected_amount AND cod_collected_amount <= cod_amount)
);
CREATE UNIQUE INDEX shipments_carrier_tracking_idx ON shipments (carrier, tracking_code) WHERE tracking_code IS NOT NULL;
CREATE INDEX shipments_status_created_idx ON shipments (status, created_at, id);

CREATE TABLE shipment_status_history (
  id uuid PRIMARY KEY,
  shipment_id uuid NOT NULL REFERENCES shipments(id),
  provider_event_key text NOT NULL,
  from_status varchar(32),
  to_status varchar(32) NOT NULL,
  provider_occurred_at timestamptz,
  sanitized_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  applied boolean NOT NULL,
  reason text,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (shipment_id, provider_event_key)
);
CREATE INDEX shipment_status_history_shipment_idx ON shipment_status_history (shipment_id, created_at);

-- Copied from platform-durability/db/platform/durability.sql (PLT-03) incl. PLT-05 trace_parent.
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
  trace_parent varchar(55),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (aggregate_type, aggregate_id, aggregate_sequence),
  CHECK ((status = 'IN_FLIGHT') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
  CHECK ((status = 'SENT') = (published_at IS NOT NULL))
);
CREATE INDEX outbox_events_unsent_idx ON outbox_events (next_attempt_at, created_at) WHERE status <> 'SENT';

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

-- Shipments, history, quotes and rule audit are never deleted by the runtime.
REVOKE DELETE, TRUNCATE ON shipments, shipment_status_history, shipping_quotes, shipping_rule_changes, shipping_rules FROM shipping_runtime;
REVOKE UPDATE ON shipment_status_history, shipping_quotes, shipping_rule_changes FROM shipping_runtime;
