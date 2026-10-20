package shieldstats

import (
	"testing"
	"time"
)

func TestDeltaBaselinesThenIncrementsAndResets(t *testing.T) {
	current := map[string]uint64{"malware": 10, "nope": 4, "ads": 0}
	delta, reset := Delta(nil, current, false)
	if delta != nil || reset {
		t.Fatalf("first sighting must not count historical totals: delta=%v reset=%v", delta, reset)
	}
	delta, reset = Delta(map[string]uint64{"malware": 10}, map[string]uint64{"malware": 12, "scam": 1}, true)
	if reset || delta["malware"] != 2 || delta["scam"] != 1 {
		t.Fatalf("delta=%v reset=%v", delta, reset)
	}
	delta, reset = Delta(map[string]uint64{"malware": 12}, map[string]uint64{"malware": 3}, true)
	if !reset || delta != nil {
		t.Fatalf("restart must not subtract: delta=%v reset=%v", delta, reset)
	}
	delta, reset = Delta(map[string]uint64{"malware": 12}, map[string]uint64{"malware": 12}, true)
	if delta != nil || reset {
		t.Fatalf("unchanged snapshot: delta=%v reset=%v", delta, reset)
	}
}

func TestUTCDaysWeekEndingToday(t *testing.T) {
	now := time.Date(2026, 10, 19, 22, 0, 0, 0, time.FixedZone("PY", -4*3600))
	days := UTCDays(now, 7)
	if len(days) != 7 || days[6] != "2026-10-20" {
		t.Fatalf("UTC date of that instant is the 20th: %v", days)
	}
	if days[0] != "2026-10-14" {
		t.Fatalf("oldest=%s", days[0])
	}
}

func TestSanitizeNoticesDropsPriorPeerAndJunk(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	created := now.Add(-time.Minute)
	got := SanitizeNotices([]Notice{
		{ID: "0123456789abcdef", Domain: "Evil.Example.", Category: "Scam", At: now.Unix()},
		{ID: "0123456789abcdef", Domain: "evil.example", Category: "scam", At: now.Unix()},
		{ID: "fedcba9876543210", Domain: "old.example", Category: "malware", At: created.Add(-time.Hour).Unix()},
		{ID: "aaaaaaaaaaaaaaaa", Domain: "not a domain", Category: "malware", At: now.Unix()},
		{ID: "bbbbbbbbbbbbbbbb", Domain: "tracker.example", Category: "other", At: now.Unix()},
	}, now, created)
	if len(got) != 1 || got[0].Domain != "evil.example" || got[0].Category != "scam" {
		t.Fatalf("got %+v", got)
	}
}

func TestAddWindowsFillsZeros(t *testing.T) {
	sum := AddWindows(nil, map[string]uint64{"malware": 2})
	if sum["malware"] != 2 || sum["ads"] != 0 || len(sum) != len(Categories) {
		t.Fatalf("%v", sum)
	}
}
