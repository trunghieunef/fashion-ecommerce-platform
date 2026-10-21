-- TASK:ORD-04: return slips and the shipment state of the saga (05; spec section 8). Append-only.

CREATE TABLE order_returns (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES orders(id),
  operation_key varchar(128) NOT NULL,
  reason text,
  evidence_ref text,
  actor_id uuid,
  status varchar(32) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'MANUAL')),
  request_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (order_id, operation_key)
);

CREATE TABLE order_return_items (
  return_id uuid NOT NULL REFERENCES order_returns(id),
  order_item_id uuid NOT NULL REFERENCES order_items(id),
  received_quantity int NOT NULL CHECK (received_quantity > 0),
  restock_quantity int NOT NULL CHECK (restock_quantity >= 0),
  condition text,
  PRIMARY KEY (return_id, order_item_id),
  CHECK (restock_quantity <= received_quantity)
);
CREATE INDEX order_return_items_item_idx ON order_return_items (order_item_id);

REVOKE DELETE, TRUNCATE ON order_returns, order_return_items FROM order_runtime;
REVOKE UPDATE ON order_return_items FROM order_runtime;

ALTER TABLE order_sagas ADD COLUMN shipment_state varchar(16) NOT NULL DEFAULT 'NONE'
  CHECK (shipment_state IN ('NONE', 'CREATED', 'CANCELLED', 'HANDED_OVER'));
UPDATE order_sagas SET shipment_state = 'CREATED' WHERE shipment_id IS NOT NULL;
