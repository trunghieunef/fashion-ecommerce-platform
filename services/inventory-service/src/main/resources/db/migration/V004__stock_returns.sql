-- TASK:INV-03: returns of committed stock (05 section 8). One row per (reservation, operation_key, sku); the
-- order lock on stock_reservations serializes returns of an order, so the cumulative guard is checked in SQL order.
CREATE TABLE stock_returns (
  id uuid PRIMARY KEY,
  reservation_id uuid NOT NULL REFERENCES stock_reservations(id),
  sku varchar(64) NOT NULL REFERENCES stock_items(sku),
  operation_key varchar(128) NOT NULL CHECK (length(operation_key) >= 1),
  quantity int NOT NULL CHECK (quantity BETWEEN 1 AND 99),
  reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
  evidence_ref text NOT NULL CHECK (length(evidence_ref) BETWEEN 1 AND 500),
  actor_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (reservation_id, operation_key, sku)
);
REVOKE UPDATE, DELETE, TRUNCATE ON stock_returns FROM inventory_runtime;
GRANT SELECT, INSERT ON stock_returns TO inventory_runtime;
