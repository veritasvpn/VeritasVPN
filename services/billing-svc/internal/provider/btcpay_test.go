package provider

import (
	"context"
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

func TestGetInvoiceStatus(t *testing.T) {
	log, _ := logging.New("error")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			t.Fatalf("expected GET, got %s", r.Method)
		}
		if got := r.Header.Get("Authorization"); got != "token key" {
			t.Fatalf("unexpected authorization header %q", got)
		}
		_, _ = w.Write([]byte(`{"id":"invoice","status":"Settled"}`))
	}))
	defer server.Close()

	b := NewBTCPayProvider(log, server.URL, "key", "store", "secret", "https://ok")
	status, err := b.GetInvoiceStatus(context.Background(), "invoice")
	if err != nil || status != "Settled" {
		t.Fatalf("expected settled status, got %q, err=%v", status, err)
	}
}
