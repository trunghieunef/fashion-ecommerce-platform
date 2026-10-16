-- TASK:INV-02: whole-order reservations; terminal rows are kept as tombstones (05 section 8).
CREATE TABLE stock_reservations (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL UNIQUE,
  order_no varchar(32) NOT NULL CHECK (length(order_no) >= 1),
  request_hash char(64) NOT NULL,
  status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','COMMITTED','RELEASED','EXPIRED')),
  expires_at timestamptz NOT NULL,
  committed_at timestamptz,
  released_at timestamptz,
  release_reason text CHECK (release_reason IS NULL OR length(release_reason) BETWEEN 1 AND 500),
  version bigint NOT NULL CHECK (version >= 1),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((status = 'COMMITTED') = (committed_at IS NOT NULL)),
  CHECK ((status IN ('RELEASED','EXPIRED')) = (released_at IS NOT NULL))
);
CREATE INDEX stock_reservations_active_expiry_idx ON stock_reservations(expires_at) WHERE status = 'ACTIVE';
CREATE TABLE stock_reservation_items (
  reservation_id uuid NOT NULL REFERENCES stock_reservations(id),
  sku varchar(64) NOT NULL REFERENCES stock_items(sku),
  quantity int NOT NULL CHECK (quantity BETWEEN 1 AND 99),
  PRIMARY KEY (reservation_id, sku)
);
REVOKE DELETE, TRUNCATE ON stock_reservations, stock_reservation_items FROM inventory_runtime;
GRANT SELECT, INSERT, UPDATE ON stock_reservations TO inventory_runtime;
GRANT SELECT, INSERT ON stock_reservation_items TO inventory_runtime;
