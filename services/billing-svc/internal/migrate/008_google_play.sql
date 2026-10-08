-- Google Play Billing. Existing rows stay Bitcoin. The service stays usable
-- when Play credentials are unset; this only widens what a configured
-- deployment is allowed to store.

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_payment_method_check;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_payment_method_check
    CHECK (payment_method IN ('stripe', 'btcpay', 'google_play', 'none'));

ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS external_ref TEXT NOT NULL DEFAULT '';

ALTER TABLE payment_records ADD COLUMN IF NOT EXISTS provider TEXT NOT NULL DEFAULT 'btcpay';

ALTER TABLE payment_records DROP CONSTRAINT IF EXISTS payment_records_provider_check;
ALTER TABLE payment_records ADD CONSTRAINT payment_records_provider_check
    CHECK (provider IN ('stripe', 'btcpay', 'google_play'));
