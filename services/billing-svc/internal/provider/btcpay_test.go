package provider

import (
	"context"
	"encoding/json"
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

func TestCreateInvoiceKeepsMinerFeeOutsideRecipientAmount(t *testing.T) {
	log, _ := logging.New("error")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Fatalf("expected POST, got %s", r.Method)
		}
		var request BTCPayInvoiceRequest
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Fatalf("decode invoice request: %v", err)
		}
		if request.Amount != "3.00" || request.Currency != "USD" {
			t.Fatalf("unexpected invoice amount: %s %s", request.Amount, request.Currency)
		}
		if request.Checkout.NetworkFeeMode != "Never" {
			t.Fatalf("miner fee must not be included in the invoice amount, got %q", request.Checkout.NetworkFeeMode)
		}
		if request.Checkout.PaymentTolerance != 5 {
			t.Fatalf("expected 5%% satoshi-rounding tolerance, got %v", request.Checkout.PaymentTolerance)
		}
		_, _ = w.Write([]byte(`{"id":"invoice","checkoutLink":"https://btcpay.example/i/invoice","status":"New"}`))
	}))
	defer server.Close()

	b := NewBTCPayProvider(log, server.URL, "key", "store", "secret", "https://ok")
	if _, _, err := b.CreateInvoice("account", "premium", "btcpay", "premium_monthly", 3); err != nil {
		t.Fatalf("create invoice: %v", err)
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
