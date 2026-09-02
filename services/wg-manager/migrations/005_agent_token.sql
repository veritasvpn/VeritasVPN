-- Per-server agent auth tokens (plaintext returned once at register).
ALTER TABLE servers ADD COLUMN IF NOT EXISTS agent_token_hash TEXT;
ALTER TABLE servers ADD COLUMN IF NOT EXISTS agent_token_issued_at TIMESTAMPTZ;
