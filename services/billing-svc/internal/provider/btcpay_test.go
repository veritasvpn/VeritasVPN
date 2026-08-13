package provider

import (
	"testing"

	"github.com/veritasvpn/lib/logging"
)

func TestVerifySignatureRequiresSecret(t *testing.T) {
	log, _ := logging.New("error")
	b := NewBTCPayProvider(log, "https://btcpay.example", "key", "store", "", "https://ok")
	if err := b.verifySignature([]byte(`{}`), "sha256=abc"); err == nil {
		t.Fatal("expected error when webhook secret empty")
	}
}
