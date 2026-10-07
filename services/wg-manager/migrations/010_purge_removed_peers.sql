-- Removed peers used to stay in the table until the account was deleted.
-- removed_at starts the 30-day purge clock. Existing removed rows are
-- backfilled from the last handshake, or from created_at when no handshake
-- was recorded, so historical rows age out on the next purge.
ALTER TABLE peers ADD COLUMN IF NOT EXISTS removed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_peers_removed_at
    ON peers (removed_at)
    WHERE status = 'removed';

UPDATE peers
   SET removed_at = COALESCE(last_handshake_at, created_at)
 WHERE status = 'removed'
   AND removed_at IS NULL;
