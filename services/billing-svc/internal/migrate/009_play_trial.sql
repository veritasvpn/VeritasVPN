-- Add trial tracking to payment records.
-- Trials are free periods offered by Google Play. They should not be counted as revenue.

ALTER TABLE payment_records ADD COLUMN IF NOT EXISTS is_trial BOOLEAN NOT NULL DEFAULT false;
