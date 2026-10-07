package migrate

import (
	"strings"
	"testing"
)

func TestRemovedPeerPurgeMigrationRecordsRemovalTime(t *testing.T) {
	body, err := files.ReadFile("010_purge_removed_peers.sql")
	if err != nil {
		t.Fatal(err)
	}
	sql := string(body)
	if !strings.Contains(sql, "removed_at") {
		t.Fatal("migration must record when a peer was removed")
	}
	if !strings.Contains(sql, "COALESCE(last_handshake_at, created_at)") {
		t.Fatal("existing removed peers must be backfilled from the last known time")
	}
	if !strings.Contains(sql, "status = 'removed'") {
		t.Fatal("backfill must apply only to removed peers")
	}
}

func TestBlockTrackersMigrationBackfillsExplicitPolicyOnce(t *testing.T) {
	body, err := files.ReadFile("009_shield_block_trackers.sql")
	if err != nil {
		t.Fatal(err)
	}
	sql := string(body)
	if !strings.Contains(sql, "shield_block_trackers") {
		t.Fatal("migration must add shield_block_trackers")
	}
	if !strings.Contains(sql, "SET shield_block_trackers = true") || !strings.Contains(sql, "shield_policy_set = true") {
		t.Fatal("existing three-flag policies must backfill trackers on")
	}
	if !strings.Contains(sql, "009_block_trackers_backfill") || !strings.Contains(sql, "pg_advisory_lock") {
		t.Fatal("backfill must be guarded so a later restart cannot undo an explicit off")
	}
	if strings.Contains(sql, "shield_policy_set = false") && strings.Contains(sql, "SET shield_block_trackers = true") {
		t.Fatal("peers with no saved flags must keep preset expansion")
	}
}
