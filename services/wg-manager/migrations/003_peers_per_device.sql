-- 003_peers_per_device.sql
-- Premium multi-device: one active peer per (account_id, device_id), not per server.

ALTER TABLE peers ADD COLUMN IF NOT EXISTS device_id TEXT;

UPDATE peers
SET device_id = 'legacy-' || id::text
WHERE device_id IS NULL OR device_id = '';

ALTER TABLE peers ALTER COLUMN device_id SET NOT NULL;

ALTER TABLE peers DROP CONSTRAINT IF EXISTS peers_account_id_server_id_key;

CREATE UNIQUE INDEX IF NOT EXISTS idx_peers_active_account_device
    ON peers (account_id, device_id)
    WHERE status IN ('pending', 'active');
