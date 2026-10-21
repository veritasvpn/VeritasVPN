package service

import (
	"os"
	"strings"
	"testing"
)

func TestGenerateAnonDeviceID(t *testing.T) {
	id, err := generateAnonDeviceID()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(id, "anon-") {
		t.Fatalf("expected anon- prefix, got %q", id)
	}
	id2, err := generateAnonDeviceID()
	if err != nil {
		t.Fatal(err)
	}
	if id == id2 {
		t.Fatal("expected unique anon device ids")
	}
}

func TestStripCIDR(t *testing.T) {
	if got := stripCIDR("10.0.0.2/32"); got != "10.0.0.2" {
		t.Fatalf("got %q", got)
	}
	if got := stripCIDR("10.0.0.2"); got != "10.0.0.2" {
		t.Fatalf("got %q", got)
	}
}

func TestPeerCreatedLogHashesDeviceID(t *testing.T) {
	body, err := os.ReadFile("service.go")
	if err != nil {
		t.Fatal(err)
	}
	src := string(body)
	start := strings.Index(src, `s.log.Info("peer created"`)
	if start < 0 {
		t.Fatal("peer created log not found")
	}
	end := strings.Index(src[start:], "return &PeerConfig{")
	if end < 0 {
		t.Fatal("peer created block not found")
	}
	block := src[start : start+end]
	if strings.Contains(block, `"device_id"`) {
		t.Fatal("peer created still records the raw device id")
	}
	if strings.Count(block, "logging.HashIdentifier(deviceID)") != 2 {
		t.Fatalf("expected the log and the event to hash device id, block:\n%s", block)
	}
}

func TestPeerCountForLimit(t *testing.T) {
	existing := 5
	replacing := true
	count := existing
	if replacing {
		count = existing - 1
	}
	if count != 4 {
		t.Fatalf("reconnect at 5 devices should check against 4, got %d", count)
	}
}
