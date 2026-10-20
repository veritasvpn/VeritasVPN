package handler

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/veritasvpn/services/wg-manager/internal/shieldstats"
)

// The bytes SetDNSNotices stores are a JSON array. GET /api/v1/wg/peers must
// put that array on shield_notices and the account counters on shield_blocks.
func TestPeersPayloadFromNoticeBytes(t *testing.T) {
	at := time.Date(2026, 10, 20, 14, 0, 0, 0, time.UTC)
	written := []shieldstats.Notice{{
		ID:       "0123456789abcdef",
		Domain:   "cheetoclient.com",
		Category: "malware",
		At:       at.Unix(),
	}}
	raw, err := json.Marshal(written)
	if err != nil {
		t.Fatal(err)
	}
	var read []shieldstats.Notice
	if err := json.Unmarshal(raw, &read); err != nil {
		t.Fatal(err)
	}
	clean := shieldstats.SanitizeNotices(read, at, at.Add(-time.Hour))
	if len(clean) != 1 || clean[0].Domain != "cheetoclient.com" || clean[0].Category != "malware" {
		t.Fatalf("sanitize dropped the stored notice: %+v", clean)
	}
	window := map[string]uint64{
		"malware": 36, "phishing": 0, "scam": 0, "crypto": 0,
		"trackers": 44, "ads": 30, "adult": 0,
	}
	peer := map[string]interface{}{
		"id":          "11111111-1111-1111-1111-111111111111",
		"assigned_ip": "10.0.0.8/32",
		"status":      "active",
	}
	putShieldListFields(peer, map[string]interface{}{"today": window, "week": window}, clean)
	body, err := json.Marshal(map[string]interface{}{"peers": []map[string]interface{}{peer}})
	if err != nil {
		t.Fatal(err)
	}

	var decoded struct {
		Peers []struct {
			ID           string `json:"id"`
			AssignedIP   string `json:"assigned_ip"`
			ShieldBlocks struct {
				Today map[string]uint64 `json:"today"`
				Week  map[string]uint64 `json:"week"`
			} `json:"shield_blocks"`
			ShieldNotices []shieldstats.Notice `json:"shield_notices"`
		} `json:"peers"`
	}
	if err := json.Unmarshal(body, &decoded); err != nil {
		t.Fatal(err)
	}
	if len(decoded.Peers) != 1 {
		t.Fatalf("peers: %s", body)
	}
	got := decoded.Peers[0]
	if got.ID != "11111111-1111-1111-1111-111111111111" || got.AssignedIP != "10.0.0.8/32" {
		t.Fatalf("peer identity: %+v", got)
	}
	if len(got.ShieldNotices) != 1 || got.ShieldNotices[0].Domain != "cheetoclient.com" || got.ShieldNotices[0].Category != "malware" || got.ShieldNotices[0].ID != "0123456789abcdef" {
		t.Fatalf("notices: %+v", got.ShieldNotices)
	}
	if got.ShieldBlocks.Today["malware"] != 36 || got.ShieldBlocks.Today["trackers"] != 44 || got.ShieldBlocks.Today["ads"] != 30 {
		t.Fatalf("today: %+v", got.ShieldBlocks.Today)
	}
	if got.ShieldBlocks.Week["malware"] != 36 {
		t.Fatalf("week: %+v", got.ShieldBlocks.Week)
	}
}

func TestPutShieldListFieldsEmitsEmptyArray(t *testing.T) {
	peer := map[string]interface{}{"id": "p"}
	putShieldListFields(peer, map[string]interface{}{"today": map[string]uint64{}, "week": map[string]uint64{}}, nil)
	body, err := json.Marshal(peer)
	if err != nil {
		t.Fatal(err)
	}
	if !json.Valid(body) {
		t.Fatal(string(body))
	}
	var decoded map[string]json.RawMessage
	if err := json.Unmarshal(body, &decoded); err != nil {
		t.Fatal(err)
	}
	if string(decoded["shield_notices"]) != "[]" {
		t.Fatalf("nil buffer must be an empty array, got %s", decoded["shield_notices"])
	}
	if _, ok := decoded["shield_blocks"]; !ok {
		t.Fatalf("missing shield_blocks: %s", body)
	}
}
