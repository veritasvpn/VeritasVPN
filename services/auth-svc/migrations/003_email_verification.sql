ALTER TABLE accounts ADD COLUMN email_verified_at TIMESTAMPTZ;
ALTER TABLE accounts ADD COLUMN verification_token_hash TEXT;
ALTER TABLE accounts ADD COLUMN verification_token_expiry TIMESTAMPTZ;
ALTER TABLE accounts ADD COLUMN verification_sent_at TIMESTAMPTZ;
ALTER TABLE accounts ADD COLUMN verification_attempts INTEGER NOT NULL DEFAULT 0;

-- Existing accounts remain usable; verification is enforced for new registrations.
UPDATE accounts SET email = LOWER(TRIM(email)), email_verified_at = created_at
WHERE email IS NOT NULL AND email_verified_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_email_normalized
    ON accounts (LOWER(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_accounts_verification_token
    ON accounts (verification_token_hash) WHERE verification_token_hash IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_accounts_unverified_cleanup
    ON accounts (created_at) WHERE email IS NOT NULL AND email_verified_at IS NULL;
