package crypto

import (
	"encoding/hex"
	"testing"
)

func TestGenerateAccountIDHas128BitsOfEntropy(t *testing.T) {
	id, err := GenerateAccountID()
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := hex.DecodeString(id)
	if err != nil {
		t.Fatalf("account ID is not hexadecimal: %v", err)
	}
	if len(decoded) != 16 {
		t.Fatalf("account ID has %d bytes; want 16", len(decoded))
	}
}
