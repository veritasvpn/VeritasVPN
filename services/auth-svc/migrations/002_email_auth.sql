ALTER TABLE accounts ADD COLUMN email TEXT UNIQUE;
ALTER TABLE accounts ADD COLUMN password_hash TEXT;
ALTER TABLE accounts ADD COLUMN reset_token TEXT;
ALTER TABLE accounts ADD COLUMN reset_token_expiry TIMESTAMPTZ;

CREATE INDEX idx_accounts_email ON accounts(email);
