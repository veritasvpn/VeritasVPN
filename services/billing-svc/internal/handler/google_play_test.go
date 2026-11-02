package handler

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/billing-svc/internal/service"
)

func TestGooglePlayEndpointsAreDisabledUntilConfigured(t *testing.T) {
	log, err := logging.New("error")
	if err != nil {
		t.Fatal(err)
	}
	svc := service.New(log, nil, nil, nil, nil, nil, service.BillingConfig{})
	h := NewBillingHandler(log, svc, nil, nil, "https://veritasvpn.cloud/account/", false)
	mux := http.NewServeMux()
	h.RegisterRoutes(mux)

	rtdn := httptest.NewRequest(http.MethodPost, "/api/v1/billing/webhook/google-play", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, rtdn)
	if rec.Code != http.StatusServiceUnavailable || !strings.Contains(rec.Body.String(), "not configured") {
		t.Fatalf("rtdn status=%d body=%s", rec.Code, rec.Body.String())
	}

	verify := httptest.NewRequest(http.MethodPost, "/api/v1/billing/google-play/verify", strings.NewReader(`{"product_id":"premium_monthly","purchase_token":"token"}`))
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, verify)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("verify without a session status=%d body=%s", rec.Code, rec.Body.String())
	}
}

func TestUnboundPlayPurchaseIsNotAcknowledged(t *testing.T) {
	log, err := logging.New("error")
	if err != nil {
		t.Fatal(err)
	}
	svc := service.New(log, nil, nil, nil, nil, nil, service.BillingConfig{})
	h := NewBillingHandler(log, svc, nil, nil, "https://veritasvpn.cloud/account/", false)
	rec := httptest.NewRecorder()
	h.writePlayError(rec, service.ErrPlayAccountUnbound)
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
	if strings.Contains(rec.Body.String(), "invalid") {
		t.Fatalf("unbound purchase was classified as a client error: %s", rec.Body.String())
	}
}

func TestRedactPlayNotificationErrorOmitsTokenAndAddress(t *testing.T) {
	err := errors.New(`google play subscription lookup: Get "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/cloud.veritasvpn/purchases/subscriptionsv2/tokens/secret-purchase-token?page=1": dial tcp 142.250.1.1:443: connect [2607:f8b0:4004:800::200e]:443`)
	got := redactPlayNotificationError(err)
	if strings.Contains(got, "secret-purchase-token") || strings.Contains(got, "142.250.1.1") || strings.Contains(got, "2607:f8b0") {
		t.Fatalf("log line leaked: %s", got)
	}
	if !strings.Contains(got, "/tokens/redacted") {
		t.Fatalf("redacted = %s", got)
	}
}
