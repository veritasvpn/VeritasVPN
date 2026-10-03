-- Speed up account purchase-history reads. Rows are the payments already stored.
CREATE INDEX IF NOT EXISTS idx_payment_records_account_created
    ON payment_records (account_id, created_at);
