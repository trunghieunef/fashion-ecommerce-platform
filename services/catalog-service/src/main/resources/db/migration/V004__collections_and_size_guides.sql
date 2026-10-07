-- TASK:CAT-01b; collection/size guide admin, append-only after V003.
CREATE TABLE collections (
  id uuid PRIMARY KEY,
  name_vi text NOT NULL,
  name_en text NOT NULL,
  slug text NOT NULL UNIQUE,
  cover_url text,
  start_at timestamptz,
  end_at timestamptz,
  status varchar(32) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','ACTIVE','INACTIVE')),
  version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (end_at > start_at)
);
CREATE TABLE collection_items (
  collection_id uuid NOT NULL REFERENCES collections(id),
  product_id uuid NOT NULL REFERENCES products(id),
  sort_order int NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
  PRIMARY KEY (collection_id, product_id)
);
CREATE INDEX collection_items_order_idx ON collection_items(collection_id, sort_order, product_id);
CREATE TABLE size_guides (
  id uuid PRIMARY KEY,
  category_id uuid NOT NULL REFERENCES categories(id),
  locale varchar(2) NOT NULL CHECK (locale IN ('vi','en')),
  guideline_html text NOT NULL DEFAULT '',
  table_json jsonb NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (category_id, locale)
);
