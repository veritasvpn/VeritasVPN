-- Add Monero (XMR) payment method support
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_payment_method_check;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_payment_method_check
    CHECK (payment_method IN ('stripe', 'btcpay', 'btcpay_xmr', 'none'));
