-- One payment_records row per Google Play order (GPA.xxx, GPA.xxx..0, ...).
-- purchase_token links those orders back to the Play token so a renewal can
-- be recorded without losing the account lookup. Pause stores the resume
-- time and does not grant Premium.

ALTER TABLE payment_records ADD COLUMN IF NOT EXISTS purchase_token TEXT NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_payment_records_purchase_token
    ON payment_records (purchase_token)
    WHERE purchase_token <> '';

ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS access_state TEXT NOT NULL DEFAULT '';
ALTER TABLE subscriptions ADD COLUMN IF NOT EXISTS pause_resumes_at TIMESTAMPTZ;
