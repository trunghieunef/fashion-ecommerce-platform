-- TASK:ORD-02: saga payment state, durable cart cleanup work, payment.events inbox; a cancelled order frees its cart state.
-- processed_events and background_tasks copy platform-durability/db/platform/durability.sql.

ALTER TABLE order_sagas ADD COLUMN payment_state varchar(16) NOT NULL DEFAULT 'NONE'
  CHECK (payment_state IN ('NONE', 'UNKNOWN', 'CREATED', 'CLOSED', 'PAID'));

-- One open order per cart state; an order cancelled before anything shipped (out of stock, refused payment, expiry) no
-- longer blocks ordering the same cart again (review PR #32, spec section 2).
ALTER TABLE orders DROP CONSTRAINT orders_cart_id_cart_version_key;
CREATE UNIQUE INDEX orders_open_cart_version_idx ON orders (cart_id, cart_version) WHERE status <> 'CANCELLED';

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
