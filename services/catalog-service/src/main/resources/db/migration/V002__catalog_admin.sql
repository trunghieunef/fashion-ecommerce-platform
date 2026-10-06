-- TASK:CAT-01a; append-only expansion of the S1 sample (05 section 5).
CREATE TABLE categories (
  id uuid PRIMARY KEY,
  parent_id uuid REFERENCES categories(id),
  name_vi text NOT NULL,
  name_en text NOT NULL,
  slug varchar(160) NOT NULL UNIQUE,
  sort_order int NOT NULL DEFAULT 0,
  status varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (parent_id <> id)
);
INSERT INTO categories(id,name_vi,name_en,slug) VALUES
  ('00000000-0000-4000-8000-000000000001','Chưa phân loại','Uncategorized','uncategorized');

CREATE TABLE brands (
  id uuid PRIMARY KEY,
  name text NOT NULL,
  logo_url text,
  status varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE products
  ADD COLUMN category_id uuid REFERENCES categories(id),
  ADD COLUMN brand_id uuid REFERENCES brands(id),
  ADD COLUMN description_vi text NOT NULL DEFAULT '',
  ADD COLUMN description_en text NOT NULL DEFAULT '',
  ADD COLUMN base_price bigint NOT NULL DEFAULT 0 CHECK (base_price >= 0),
  ADD COLUMN tags text[] NOT NULL DEFAULT '{}',
  ADD COLUMN published_at timestamptz,
  ADD COLUMN sold_quantity bigint NOT NULL DEFAULT 0 CHECK (sold_quantity >= 0);
UPDATE products SET category_id='00000000-0000-4000-8000-000000000001';
ALTER TABLE products ALTER COLUMN category_id SET NOT NULL, ALTER COLUMN base_price DROP DEFAULT;
CREATE INDEX products_category_status_idx ON products(category_id,status,published_at,id);

CREATE TABLE product_variants (
  id uuid PRIMARY KEY,
  product_id uuid NOT NULL REFERENCES products(id),
  sku varchar(64) NOT NULL,
  size text NOT NULL,
  color text NOT NULL,
  price_override bigint CHECK (price_override >= 0),
  weight_grams int NOT NULL CHECK (weight_grams > 0),
  status varchar(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT product_variants_sku_key UNIQUE(sku),
  CONSTRAINT product_variants_product_size_color_key UNIQUE(product_id,size,color)
);
CREATE FUNCTION enforce_variant_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.sku IS DISTINCT FROM OLD.sku OR NEW.size IS DISTINCT FROM OLD.size
     OR NEW.color IS DISTINCT FROM OLD.color OR NEW.product_id IS DISTINCT FROM OLD.product_id THEN
    RAISE EXCEPTION 'variant identity is immutable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER product_variants_immutable BEFORE UPDATE ON product_variants
  FOR EACH ROW EXECUTE FUNCTION enforce_variant_identity();

-- Same service-owned durability DDL as user-service; no shared database.
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
CREATE INDEX outbox_events_unsent_idx ON outbox_events (next_attempt_at, created_at) WHERE status <> 'SENT';
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
REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs FROM catalog_runtime;
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
