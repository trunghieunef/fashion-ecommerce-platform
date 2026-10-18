-- TASK:CART-01: guest/member carts (05 section 6, CART-01 spec section 4). PostgreSQL is the source of truth.
CREATE TABLE carts (
  id uuid PRIMARY KEY,
  user_id uuid,
  guest_token_hash char(64),
  status varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'MERGED', 'CHECKED_OUT', 'ABANDONED')),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  expires_at timestamptz,
  merged_into uuid REFERENCES carts(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((user_id IS NULL) <> (guest_token_hash IS NULL))
);
CREATE UNIQUE INDEX carts_one_active_member ON carts (user_id) WHERE status = 'ACTIVE' AND user_id IS NOT NULL;
CREATE UNIQUE INDEX carts_guest_token ON carts (guest_token_hash) WHERE guest_token_hash IS NOT NULL;
CREATE INDEX carts_expires_at ON carts (expires_at);

CREATE TABLE cart_items (
  id uuid PRIMARY KEY,
  cart_id uuid NOT NULL REFERENCES carts(id),
  variant_id uuid NOT NULL,
  sku varchar(64) NOT NULL,
  quantity int NOT NULL CHECK (quantity BETWEEN 1 AND 99),
  price_snapshot bigint NOT NULL CHECK (price_snapshot >= 0),
  catalog_version bigint NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (cart_id, variant_id)
);

-- Copied from platform-durability/db/platform/durability.sql (cart add retries, 03 section 1.3).
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

-- Carts are never deleted by the runtime (status changes only); items are removed by the owner.
REVOKE DELETE, TRUNCATE ON carts FROM cart_runtime;
