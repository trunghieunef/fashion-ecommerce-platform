-- TASK:PAY-02: VNPay link. provider_created_at fixes vnp_CreateDate for the life of the TxnRef; payment_url_expires_at is the
-- stored vnp_ExpireDate so a replay returns identical values whatever the config says later.
ALTER TABLE payments ADD COLUMN provider_created_at timestamptz;
ALTER TABLE payments ADD COLUMN payment_url_expires_at timestamptz;
