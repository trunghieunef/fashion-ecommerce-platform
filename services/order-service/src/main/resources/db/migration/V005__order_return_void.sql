-- TASK:ORD-04 (PR #35 review I2): OPS can void a MANUAL return slip. A slip goes MANUAL only on an inventory refusal, which
-- writes no stock, so a VOIDED slip no longer counts in the received sum and the goods can be counted again. Append-only.

ALTER TABLE order_returns DROP CONSTRAINT order_returns_status_check;
ALTER TABLE order_returns ADD CONSTRAINT order_returns_status_check
  CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'MANUAL', 'VOIDED'));
