-- Hourly maintenance finds pending Bitcoin payments older than 4 days.
CREATE INDEX IF NOT EXISTS idx_payment_records_pending_created
    ON payment_records (created_at)
    WHERE status = 'pending';
