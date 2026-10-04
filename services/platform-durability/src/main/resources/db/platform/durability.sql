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

-- 05 section 15: async work outside a saga (cart cleanup, cache invalidation); create only where needed.
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
