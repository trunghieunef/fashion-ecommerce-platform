-- TASK:SHP-02: every status after hand-over carries handed_over_at (the order goes SHIPPING only on that mark).
ALTER TABLE shipments ADD CONSTRAINT shipments_handed_over_check CHECK (
  handed_over_at IS NOT NULL OR status IN ('PENDING', 'UNKNOWN', 'CREATED', 'PICKING', 'CANCEL_PENDING', 'CANCELLED', 'MANUAL'));
