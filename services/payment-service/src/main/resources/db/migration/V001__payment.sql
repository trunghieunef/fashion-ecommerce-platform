-- TASK:PAY-01: payments, evidence, COD settlements and reconciliation items (05 section 9, PAY-01 spec section 3).
CREATE TABLE payments (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL UNIQUE,
  order_no varchar(32) NOT NULL UNIQUE CHECK (length(order_no) >= 1),
  user_id uuid,
  method varchar(16) NOT NULL CHECK (method IN ('COD', 'VNPAY', 'MOMO')),
  amount bigint NOT NULL CHECK (amount >= 0),
  currency char(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
  status varchar(32) NOT NULL DEFAULT 'CREATED' CHECK (status IN ('CREATED','PROCESSING','UNKNOWN','SUCCESS','FAILED',
    'PARTIALLY_REFUNDED','REFUNDED','COD_PENDING','COD_COLLECTED')),
  -- PAY-07: one business reference for the whole life of the payment (order_no in PAY-01).
  provider_request_id varchar(128) NOT NULL UNIQUE,
  provider_txn_id text,
  payment_url text,
  closed_at timestamptz,
  close_reason text CHECK (length(close_reason) BETWEEN 1 AND 500),
  request_hash char(64) NOT NULL,
  -- New against 05: the hashed order snapshot, kept for the close-before-create tombstone.
  snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
  expires_at timestamptz NOT NULL,
  paid_at timestamptz,
  refunded_amount bigint NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
  refund_reserved_amount bigint NOT NULL DEFAULT 0 CHECK (refund_reserved_amount >= 0),
  next_reconcile_at timestamptz,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (refunded_amount + refund_reserved_amount <= amount),
  CHECK ((closed_at IS NULL) = (close_reason IS NULL)),
  CHECK (status <> 'SUCCESS' OR paid_at IS NOT NULL)
);
CREATE UNIQUE INDEX payments_method_txn_idx ON payments (method, provider_txn_id) WHERE provider_txn_id IS NOT NULL;
CREATE INDEX payments_reconcile_idx ON payments (status, next_reconcile_at);
CREATE INDEX payments_created_idx ON payments (created_at, id);

-- One evidence row per state change or provider/carrier fact; source CLOSE/SETTLEMENT are new against 05.
CREATE TABLE payment_transactions (
  id uuid PRIMARY KEY,
  payment_id uuid NOT NULL REFERENCES payments(id),
  source varchar(16) NOT NULL CHECK (source IN ('CREATE','WEBHOOK','QUERY','COD','CLOSE','SETTLEMENT')),
  provider_event_key text NOT NULL,
  provider_txn_id text,
  status varchar(32) NOT NULL,
  amount bigint CHECK (amount >= 0),
  occurred_at timestamptz,
  sanitized_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  payload_hash char(64) NOT NULL,
  -- clock_timestamp(): rows of one transaction keep their order for (created_at, id) reads.
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (payment_id, source, provider_event_key)
);
CREATE INDEX payment_transactions_payment_idx ON payment_transactions (payment_id, created_at);

-- 05 supplementary table: FINANCE proof that money arrived; gross = fee + net.
CREATE TABLE cod_settlements (
  id uuid PRIMARY KEY,
  payment_id uuid NOT NULL REFERENCES payments(id),
  settlement_ref text NOT NULL CHECK (length(settlement_ref) BETWEEN 1 AND 128),
  gross_amount bigint NOT NULL CHECK (gross_amount >= 0),
  fee_amount bigint NOT NULL CHECK (fee_amount >= 0),
  net_amount bigint NOT NULL CHECK (net_amount >= 0),
  evidence_ref text NOT NULL CHECK (length(evidence_ref) BETWEEN 1 AND 500),
  confirmed_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK (gross_amount = fee_amount + net_amount),
  UNIQUE (payment_id, settlement_ref)
);

CREATE TABLE reconciliation_items (
  id uuid PRIMARY KEY,
  payment_id uuid NOT NULL REFERENCES payments(id),
  source_ref text NOT NULL,
  kind varchar(32) NOT NULL,
  expected_amount bigint NOT NULL,
  observed_amount bigint NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
  resolved_by uuid,
  resolved_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE (payment_id, source_ref, kind)
);
CREATE INDEX reconciliation_items_status_idx ON reconciliation_items (status, created_at);

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

-- Money records are never deleted by the runtime; evidence and settlements are append-only.
REVOKE DELETE, TRUNCATE ON payments, payment_transactions, cod_settlements, reconciliation_items FROM payment_runtime;
REVOKE UPDATE ON payment_transactions, cod_settlements FROM payment_runtime;
