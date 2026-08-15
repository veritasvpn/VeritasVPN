ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS plan_id TEXT NOT NULL DEFAULT 'free';
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS billing_period TEXT NOT NULL DEFAULT 'lifetime';
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS price_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS period_days INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payment_records ADD COLUMN IF NOT EXISTS plan_id TEXT NOT NULL DEFAULT 'premium_monthly';
ALTER TABLE payment_records ADD COLUMN IF NOT EXISTS period_days INTEGER NOT NULL DEFAULT 30;
UPDATE subscriptions SET plan_id=CASE WHEN tier='premium' THEN 'premium_monthly' ELSE 'free' END,
 billing_period=CASE WHEN tier='premium' THEN 'monthly' ELSE 'lifetime' END,
 price_cents=CASE WHEN tier='premium' THEN 300 ELSE 0 END,
 period_days=CASE WHEN tier='premium' THEN 30 ELSE 0 END
 WHERE (tier='premium' AND (plan_id IS NULL OR plan_id='free')) OR (tier='free' AND (plan_id IS NULL OR plan_id <> 'free'));
CREATE INDEX IF NOT EXISTS idx_subscriptions_plan ON subscriptions(plan_id);
CREATE INDEX IF NOT EXISTS idx_payment_records_plan ON payment_records(plan_id);
