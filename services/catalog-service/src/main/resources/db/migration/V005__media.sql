-- TASK:CAT-03; safe media (upload, approved assets, product/lookbook images), append-only after V004.
CREATE TABLE media_uploads (
  id uuid PRIMARY KEY,
  actor_id uuid NOT NULL,
  target_type varchar(16) NOT NULL CHECK (target_type IN ('PRODUCT','COLLECTION')),
  product_id uuid REFERENCES products(id),
  collection_id uuid REFERENCES collections(id),
  filename varchar(255) NOT NULL,
  content_type varchar(16) NOT NULL CHECK (content_type IN ('image/jpeg','image/png')),
  size_bytes integer NOT NULL CHECK (size_bytes BETWEEN 1 AND 5242880),
  quarantine_key text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  put_expires_at timestamptz NOT NULL,
  complete_deadline timestamptz NOT NULL,
  state varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK (state IN ('PENDING','PROCESSING','APPROVED','REJECTED','EXPIRED')),
  attempt integer NOT NULL DEFAULT 0 CHECK (attempt >= 0),
  lease_token uuid,
  lease_until timestamptz,
  reason_code varchar(64),
  terminal_at timestamptz,
  quarantine_cleaned_at timestamptz,
  quarantine_lease_token uuid,
  quarantine_lease_until timestamptz,
  quarantine_attempts integer NOT NULL DEFAULT 0,
  quarantine_next_retry_at timestamptz NOT NULL DEFAULT now(),
  partial_cleaned_at timestamptz,
  CHECK ((target_type = 'PRODUCT') = (product_id IS NOT NULL)),
  CHECK ((target_type = 'COLLECTION') = (collection_id IS NOT NULL)),
  CHECK ((state = 'PROCESSING') = (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
  CHECK ((state IN ('APPROVED','REJECTED','EXPIRED')) = (terminal_at IS NOT NULL)),
  CHECK ((state = 'REJECTED') = (reason_code IS NOT NULL)),
  CHECK (complete_deadline = put_expires_at + interval '24 hours'),
  UNIQUE (id, product_id),
  UNIQUE (id, collection_id)
);
CREATE INDEX media_uploads_sweep_idx ON media_uploads (quarantine_next_retry_at) WHERE quarantine_cleaned_at IS NULL;
CREATE TABLE media_assets (
  id uuid PRIMARY KEY REFERENCES media_uploads(id),
  image_key text NOT NULL UNIQUE,
  thumb_key text NOT NULL UNIQUE,
  image_content_type varchar(16) NOT NULL,
  image_size_bytes integer NOT NULL,
  image_width integer NOT NULL,
  image_height integer NOT NULL,
  image_sha256 char(64) NOT NULL,
  thumb_content_type varchar(16) NOT NULL,
  thumb_size_bytes integer NOT NULL,
  thumb_width integer NOT NULL,
  thumb_height integer NOT NULL,
  thumb_sha256 char(64) NOT NULL,
  approved_at timestamptz NOT NULL DEFAULT now(),
  availability varchar(16) NOT NULL DEFAULT 'AVAILABLE' CHECK (availability IN ('AVAILABLE','DELETING','DELETED')),
  detached_at timestamptz,
  gc_attempts integer NOT NULL DEFAULT 0,
  gc_next_retry_at timestamptz NOT NULL DEFAULT now(),
  gc_error varchar(64)
);
CREATE TABLE product_images (
  product_id uuid NOT NULL REFERENCES products(id),
  asset_id uuid NOT NULL UNIQUE REFERENCES media_assets(id),
  alt_vi varchar(255) NOT NULL CHECK (char_length(alt_vi) BETWEEN 1 AND 255),
  alt_en varchar(255) NOT NULL CHECK (char_length(alt_en) BETWEEN 1 AND 255),
  variant_color text,
  sort_order integer NOT NULL CHECK (sort_order >= 0),
  PRIMARY KEY (product_id, asset_id),
  FOREIGN KEY (asset_id, product_id) REFERENCES media_uploads(id, product_id)
);
CREATE INDEX product_images_order_idx ON product_images (product_id, sort_order, asset_id);
CREATE TABLE lookbook_images (
  collection_id uuid NOT NULL REFERENCES collections(id),
  asset_id uuid NOT NULL UNIQUE REFERENCES media_assets(id),
  alt_vi varchar(255) NOT NULL CHECK (char_length(alt_vi) BETWEEN 1 AND 255),
  alt_en varchar(255) NOT NULL CHECK (char_length(alt_en) BETWEEN 1 AND 255),
  caption_vi varchar(500) NOT NULL DEFAULT '',
  caption_en varchar(500) NOT NULL DEFAULT '',
  sort_order integer NOT NULL CHECK (sort_order >= 0),
  PRIMARY KEY (collection_id, asset_id),
  FOREIGN KEY (asset_id, collection_id) REFERENCES media_uploads(id, collection_id)
);
CREATE INDEX lookbook_images_order_idx ON lookbook_images (collection_id, sort_order, asset_id);
ALTER TABLE collections ADD COLUMN cover_asset_id uuid;
ALTER TABLE collections ADD FOREIGN KEY (id, cover_asset_id) REFERENCES lookbook_images(collection_id, asset_id)
  DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE media_job_leases (
  id uuid PRIMARY KEY,
  lease_token uuid,
  lease_until timestamptz
);
INSERT INTO media_job_leases (id) VALUES ('00000000-0000-4000-8000-00000000c003');
