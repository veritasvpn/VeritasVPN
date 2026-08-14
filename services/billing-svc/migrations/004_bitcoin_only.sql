-- Retire Monero checkout while preserving historical subscriptions as BTCPay records.
UPDATE subscriptions
SET payment_method = 'btcpay'
WHERE payment_method = 'btcpay_xmr';

ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_payment_method_check;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_payment_method_check
    CHECK (payment_method IN ('stripe', 'btcpay', 'none'));
