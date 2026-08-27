package jwt

import (
	"testing"
)

func TestGenerateJTIIsUnique(t *testing.T) {
	a := generateJTI()
	b := generateJTI()
	if len(a) != 32 || len(b) != 32 {
		t.Fatalf("expected 32 hex chars, got %q and %q", a, b)
	}
	if a == b {
		t.Fatal("JTI must not be identical across calls")
	}
	if a == "00000000000000000000000000000000" {
		t.Fatal("JTI must not be all zeros")
	}
}
