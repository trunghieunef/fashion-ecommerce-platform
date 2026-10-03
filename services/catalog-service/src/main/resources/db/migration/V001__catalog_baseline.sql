CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE products (
  id uuid PRIMARY KEY,
  slug varchar(160) NOT NULL UNIQUE,
  status varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
  name_vi varchar(255) NOT NULL,
  name_en varchar(255) NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX products_public_order_idx
  ON products(created_at DESC, id DESC)
  WHERE status = 'ACTIVE';
