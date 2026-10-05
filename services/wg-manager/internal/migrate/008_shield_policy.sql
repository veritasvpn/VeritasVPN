-- Independent Veritas Shield toggles.
-- shield_policy_set false keeps shield_preset (existing peers stay on Standard).
ALTER TABLE peers ADD COLUMN IF NOT EXISTS shield_policy_set BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE peers ADD COLUMN IF NOT EXISTS shield_block_malicious BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE peers ADD COLUMN IF NOT EXISTS shield_block_ads BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE peers ADD COLUMN IF NOT EXISTS shield_block_adult BOOLEAN NOT NULL DEFAULT false;
