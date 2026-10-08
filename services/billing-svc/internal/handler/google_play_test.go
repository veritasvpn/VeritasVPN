package handler

import (
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
