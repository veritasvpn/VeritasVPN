-- Fourth Premium Shield toggle. The column default stays false so peers with
-- no saved policy still expand shield_preset (Standard keeps trackers on).
ALTER TABLE peers ADD COLUMN IF NOT EXISTS shield_block_trackers BOOLEAN NOT NULL DEFAULT false;

-- One-time backfill. Peers that already saved the three-flag policy had
-- trackers forced on by the agent. Setting the column true preserves that
-- coverage. The sentinel keeps a later process restart from turning an
-- explicit "off" back on. The advisory lock serializes the first apply when
-- more than one process starts at once.
CREATE TABLE IF NOT EXISTS shield_policy_migrations (
  id text PRIMARY KEY,
  applied_at timestamptz NOT NULL DEFAULT now()
);

DO $$
BEGIN
  PERFORM pg_advisory_lock(hashtext('009_block_trackers_backfill')::bigint);
  IF NOT EXISTS (
    SELECT 1 FROM shield_policy_migrations WHERE id = '009_block_trackers_backfill'
  ) THEN
    UPDATE peers
       SET shield_block_trackers = true
     WHERE shield_policy_set = true;
    INSERT INTO shield_policy_migrations (id)
    VALUES ('009_block_trackers_backfill')
    ON CONFLICT (id) DO NOTHING;
  END IF;
  PERFORM pg_advisory_unlock(hashtext('009_block_trackers_backfill')::bigint);
END $$;
