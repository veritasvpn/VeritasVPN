-- Allow subscriptions created through BTCPay Server's Monero checkout flow.
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_payment_method_check;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_payment_method_check
    CHECK (payment_method IN ('stripe', 'btcpay', 'btcpay_xmr', 'none'));
