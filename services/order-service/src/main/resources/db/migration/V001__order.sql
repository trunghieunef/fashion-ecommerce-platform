-- TASK:ORD-01: orders with immutable snapshots, status history, saga row for ORD-02 and outbox (05 section 7).

CREATE SEQUENCE order_no_seq;

CREATE TABLE orders (
  id uuid PRIMARY KEY,
  order_no varchar(32) NOT NULL UNIQUE,
  user_id uuid,
  actor_key text NOT NULL,
  guest_access_hash char(64),
  checkout_key varchar(128) NOT NULL,
  checkout_hash char(64) NOT NULL,
  cart_id uuid NOT NULL,
  cart_version bigint NOT NULL CHECK (cart_version >= 0),
  status varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'WAITING_PAYMENT', 'CONFIRMED', 'PAID',
    'PACKING', 'SHIPPING', 'DELIVERED', 'CANCELLING', 'CANCELLED', 'REFUNDING', 'REFUNDED', 'RETURNING', 'RETURNED')),
  payment_method varchar(16) NOT NULL CHECK (payment_method IN ('COD', 'VNPAY', 'MOMO')),
  currency char(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
  subtotal bigint NOT NULL CHECK (subtotal >= 0),
  discount_amount bigint NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
  shipping_fee bigint NOT NULL CHECK (shipping_fee >= 0),
  shipping_discount bigint NOT NULL DEFAULT 0 CHECK (shipping_discount >= 0),
  cod_fee bigint NOT NULL DEFAULT 0 CHECK (cod_fee >= 0),
  total_amount bigint NOT NULL CHECK (total_amount >= 0),
  voucher_code text,
  contact_email text,
  contact_phone varchar(32) NOT NULL,
  locale varchar(2) NOT NULL DEFAULT 'vi' CHECK (locale IN ('vi', 'en')),
  shipping_address jsonb NOT NULL CHECK (jsonb_typeof(shipping_address) = 'object'),
  shipping_quote jsonb NOT NULL CHECK (jsonb_typeof(shipping_quote) = 'object'),
  note text,
  expires_at timestamptz NOT NULL,
  paid_at timestamptz,
  confirmed_at timestamptz,
  delivered_at timestamptz,
  cancel_reason text,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (actor_key, checkout_key),
  -- One order per cart state: a double-click or a second tab with a new key cannot order the same cart twice.
  UNIQUE (cart_id, cart_version),
  CHECK (discount_amount <= subtotal),
  CHECK (shipping_discount <= shipping_fee),
  CHECK (total_amount = subtotal - discount_amount + shipping_fee - shipping_discount + cod_fee),
  -- A guest order is only readable through its access credential; a member order has none.
  CHECK ((user_id IS NULL) = (guest_access_hash IS NOT NULL)),
  CHECK ((user_id IS NULL) = (actor_key LIKE 'guest:%'))
);
CREATE INDEX orders_user_idx ON orders (user_id, created_at, id);
CREATE INDEX orders_status_idx ON orders (status, created_at, id);
CREATE INDEX orders_expiry_idx ON orders (expires_at) WHERE status IN ('PENDING', 'WAITING_PAYMENT', 'CONFIRMED');

CREATE TABLE order_items (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES orders(id),
  variant_id uuid NOT NULL,
  sku varchar(64) NOT NULL,
  name text NOT NULL,
  size text NOT NULL,
  color text NOT NULL,
  image_url text,
  quantity int NOT NULL CHECK (quantity BETWEEN 1 AND 99),
  unit_price bigint NOT NULL CHECK (unit_price >= 0),
  line_total bigint NOT NULL,
  catalog_version bigint NOT NULL,
  campaign_item_id uuid,
  cart_item_id uuid NOT NULL,
  cart_item_version bigint NOT NULL,
  UNIQUE (order_id, variant_id),
  CHECK (line_total = quantity * unit_price)
);

-- Guest read credentials (spec section 7, review I1): every issued access token stays valid; a retry adds one, never
-- replaces, so two racing responses cannot leave the browser with a dead cookie. orders.guest_access_hash is the first.
CREATE TABLE order_guest_access (
  access_hash char(64) PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES orders(id),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX order_guest_access_order_idx ON order_guest_access (order_id);

CREATE TABLE order_status_history (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES orders(id),
  from_status varchar(32),
  to_status varchar(32) NOT NULL,
  actor_type varchar(16) NOT NULL CHECK (actor_type IN ('SYSTEM', 'CUSTOMER', 'ADMIN')),
  actor_id uuid,
  reason text,
  request_id uuid,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX order_status_history_order_idx ON order_status_history (order_id, created_at, id);

CREATE TABLE order_sagas (
  order_id uuid PRIMARY KEY REFERENCES orders(id),
  intent varchar(16) NOT NULL DEFAULT 'FULFILL' CHECK (intent IN ('FULFILL', 'CANCEL')),
  step varchar(32) NOT NULL,
  status varchar(32) NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'WAITING', 'COMPENSATING', 'DONE', 'MANUAL')),
  inventory_state varchar(32) NOT NULL DEFAULT 'NONE',
  promotion_state varchar(32) NOT NULL DEFAULT 'NONE',
  payment_id uuid,
  shipment_id uuid,
  retry_count int NOT NULL DEFAULT 0,
  next_retry_at timestamptz NOT NULL DEFAULT now(),
  lease_until timestamptz,
  lease_token uuid,
  last_error text,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (inventory_state IN ('NONE', 'UNKNOWN', 'HELD', 'COMMITTED', 'RELEASED', 'RESTORED')),
  CHECK (promotion_state IN ('NONE', 'UNKNOWN', 'HELD', 'COMMITTED', 'RELEASED', 'RESTORED'))
);
CREATE INDEX order_sagas_due_idx ON order_sagas (next_retry_at) WHERE status IN ('RUNNING', 'WAITING', 'COMPENSATING');

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

-- Orders and their history are never deleted by the runtime; history is append-only.
REVOKE DELETE, TRUNCATE ON orders, order_items, order_status_history, order_guest_access FROM order_runtime;
REVOKE UPDATE ON order_status_history FROM order_runtime;
