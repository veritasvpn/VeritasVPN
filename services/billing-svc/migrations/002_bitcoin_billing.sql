-- Bitcoin billing hardening: pending subscriptions + payment idempotency

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_status_check;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_status_check
    CHECK (status IN ('active', 'canceled', 'past_due', 'pending'));

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_payment_method_check;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_payment_method_check
    CHECK (payment_method IN ('stripe', 'btcpay', 'none'));

ALTER TABLE payment_records
    ADD COLUMN IF NOT EXISTS account_id TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_records_provider_txn_unique
    ON payment_records(provider_transaction_id);

CREATE INDEX IF NOT EXISTS idx_subscriptions_period_end
    ON subscriptions(current_period_end)
    WHERE tier = 'premium' AND status = 'active';
