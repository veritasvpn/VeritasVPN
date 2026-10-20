package dns

import (
	"encoding/binary"
	"testing"
	"time"
)

func TestBlockedResponseIsNXDOMAIN(t *testing.T) {
	query := []byte{
		0x12, 0x34, 0x01, 0x00,
		0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x03, 'w', 'w', 'w', 0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 0x03, 'c', 'o', 'm', 0x00,
		0x00, 0x01, 0x00, 0x01,
	}
	name, end, ok := queryName(query)
	if !ok || name != "www.example.com" {
		t.Fatalf("name=%q ok=%v", name, ok)
	}
	resp := blockedResponse(query, end)
	if len(resp) != end {
		t.Fatalf("blocked response len=%d want %d (no answer records)", len(resp), end)
	}
	flags := binary.BigEndian.Uint16(resp[2:4])
	if flags&0x000F != 0x0003 {
		t.Fatalf("rcode=%d want NXDOMAIN 3", flags&0x000F)
	}
	if binary.BigEndian.Uint16(resp[6:8]) != 0 {
		t.Fatal("answer count must stay 0")
	}
}

func TestNoticeRingDedupCapAndClear(t *testing.T) {
	f := &Forwarder{}
	now := time.Unix(1_700_000_000, 0)
	f.noteBlock("10.0.0.8:53", "Evil.Example.", CategoryMalware, now)
	f.noteBlock("10.0.0.8", "evil.example", CategoryMalware, now.Add(time.Minute))
	snap := f.NoticeSnapshot(now.Add(time.Minute))
	if len(snap["10.0.0.8"]) != 1 {
		t.Fatalf("dedup failed: %+v", snap["10.0.0.8"])
	}
	if snap["10.0.0.8"][0].Domain != "evil.example" || snap["10.0.0.8"][0].Category != CategoryMalware {
		t.Fatalf("notice=%+v", snap["10.0.0.8"][0])
	}
	counts := f.BlockedCategoryCounts()
	if counts["10.0.0.8"][CategoryMalware] != 2 {
		t.Fatalf("queries still count twice, got %+v", counts)
	}
	if f.BlockedCounts()["10.0.0.8"] != 2 {
		t.Fatal("session total must still increment per query")
	}

	for i := 0; i < 20; i++ {
		f.noteBlock("10.0.0.8", "m"+string(rune('a'+i%26))+".example", CategoryPhishing, now.Add(time.Duration(i)*time.Second))
	}
	// Distinct domains within the dedup window. Cap the ring.
	if got := len(f.NoticeSnapshot(now.Add(30 * time.Second))["10.0.0.8"]); got > noticeRingCap {
		t.Fatalf("ring=%d cap=%d", got, noticeRingCap)
	}

	f.noteBlock("10.0.0.8", "ads.example", CategoryAds, now)
	f.noteBlock("10.0.0.8", "other-ads.example", CategoryAds, now.Add(time.Second))
	ads := 0
	for _, n := range f.NoticeSnapshot(now.Add(2 * time.Second))["10.0.0.8"] {
		if n.Category == CategoryAds {
			ads++
			if n.Domain != "other-ads.example" {
				t.Fatalf("ads slot should keep the latest name, got %s", n.Domain)
			}
		}
	}
	if ads != 1 {
		t.Fatalf("ads slots=%d want 1", ads)
	}

	f.ClearBlockedForCIDRs([]string{"10.0.0.8/32"})
	if len(f.NoticeSnapshot(now)["10.0.0.8"]) != 0 || len(f.BlockedCategoryCounts()) != 0 || len(f.BlockedCounts()) != 0 {
		t.Fatal("clearing the peer must drop counts and names")
	}
}

func TestNoticeSnapshotDropsExpired(t *testing.T) {
	f := &Forwarder{}
	now := time.Unix(1_700_000_000, 0)
	f.noteBlock("10.0.0.4", "old.example", CategoryScam, now)
	if len(f.NoticeSnapshot(now.Add(noticeMaxAge + time.Second))["10.0.0.4"]) != 0 {
		t.Fatal("expired notice must leave memory")
	}
}

func TestNoteBlockIgnoresUnknownCategory(t *testing.T) {
	f := &Forwarder{}
	f.noteBlock("10.0.0.4", "x.example", "not-a-category", time.Unix(1, 0))
	if len(f.BlockedCounts()) != 0 || len(f.NoticeSnapshot(time.Unix(1, 0))) != 0 {
		t.Fatal("unknown category must not be stored")
	}
}
