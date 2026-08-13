package service

import (
	"encoding/base64"
	"testing"
)

func TestGeneratePSK(t *testing.T) {
	psk, err := generatePSK()
	if err != nil {
		t.Fatalf("generatePSK: %v", err)
	}
	raw, err := base64.StdEncoding.DecodeString(psk)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(raw) != 32 {
		t.Fatalf("want 32 bytes, got %d", len(raw))
	}
	psk2, err := generatePSK()
	if err != nil {
		t.Fatalf("generatePSK second: %v", err)
	}
	if psk == psk2 {
		t.Fatal("expected unique PSKs")
	}
}
