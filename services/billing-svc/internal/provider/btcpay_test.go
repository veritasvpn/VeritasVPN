package provider

import (
	"errors"
	"net/http"
	"net/http/httptest"
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

func TestCreateInvoiceClassifiesWalletNotConfigured(t *testing.T) {
	log, _ := logging.New("error")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"code":"generic-error","message":"no wallet has been linked to your BTCPay Store"}`))
	}))
	defer server.Close()

	b := NewBTCPayProvider(log, server.URL, "key", "store", "secret", "https://ok")
	_, _, err := b.CreateInvoice("account", "premium", "btcpay", "monthly", 3)
	if !errors.Is(err, ErrStoreWalletNotConfigured) {
		t.Fatalf("expected ErrStoreWalletNotConfigured, got %v", err)
	}
}
