-- Veritas Shield Phase 2: per-peer DNS policy preset.
ALTER TABLE peers ADD COLUMN IF NOT EXISTS shield_preset TEXT NOT NULL DEFAULT 'standard';
UPDATE peers SET shield_preset = 'standard' WHERE shield_preset IS NULL OR shield_preset = '';
ALTER TABLE peers DROP CONSTRAINT IF EXISTS peers_shield_preset_check;
ALTER TABLE peers ADD CONSTRAINT peers_shield_preset_check
  CHECK (shield_preset IN ('security', 'standard', 'aggressive'));
