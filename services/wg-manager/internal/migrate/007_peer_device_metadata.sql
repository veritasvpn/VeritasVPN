-- 007_peer_device_metadata.sql
-- Human-readable, privacy-minimised peer metadata. These fields are supplied
-- by a signed-in client and are display-only: they are never used for auth or
-- WireGuard routing decisions.
ALTER TABLE peers ADD COLUMN IF NOT EXISTS device_name TEXT NOT NULL DEFAULT '';
ALTER TABLE peers ADD COLUMN IF NOT EXISTS device_platform TEXT NOT NULL DEFAULT '';
ALTER TABLE peers ADD COLUMN IF NOT EXISTS device_model TEXT NOT NULL DEFAULT '';
ALTER TABLE peers ADD COLUMN IF NOT EXISTS device_os_version TEXT NOT NULL DEFAULT '';
ALTER TABLE peers ADD COLUMN IF NOT EXISTS client_version TEXT NOT NULL DEFAULT '';
ALTER TABLE peers ADD COLUMN IF NOT EXISTS last_handshake_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_peers_server_last_handshake
    ON peers (server_id, last_handshake_at DESC)
    WHERE status IN ('pending', 'active');
