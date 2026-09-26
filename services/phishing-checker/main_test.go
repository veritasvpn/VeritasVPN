package main

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestPublicAddressClassification(t *testing.T) {
	cases := []struct {
		value string
		want  bool
	}{
		{"8.8.8.8", true},
		{"1.1.1.1", true},
		{"127.0.0.1", false},
		{"10.0.0.1", false},
		{"192.168.0.6", false},
		{"100.64.0.1", false},
		{"198.51.100.10", false},
		{"::1", false},
	}
	for _, tc := range cases {
		if got := isPublicIP(net.ParseIP(tc.value)); got != tc.want {
			t.Errorf("isPublicIP(%s) = %v, want %v", tc.value, got, tc.want)
		}
	}
}

func TestRejectsPrivateTarget(t *testing.T) {
	if _, err := analyze(context.Background(), "https://192.168.0.6"); err == nil {
		t.Fatal("expected private target to be rejected")
	}
}

func TestCheckRequiresTurnstileBeforeLookup(t *testing.T) {
	handler := check(&limiter{entries: map[string][]time.Time{}, limit: 10, window: time.Minute}, "secret", verifyTurnstile)
	body := strings.NewReader(`{"url":"https://127.0.0.1"}`)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/phishing/check", body)
	req.Header.Set("Origin", "https://veritasvpn.cloud")
	rec := httptest.NewRecorder()
	handler(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("missing token status = %d, want 403", rec.Code)
	}

	unavailable := check(&limiter{entries: map[string][]time.Time{}, limit: 10, window: time.Minute}, "", verifyTurnstile)
	req = httptest.NewRequest(http.MethodPost, "/api/v1/phishing/check", strings.NewReader(`{"url":"https://127.0.0.1","turnstile_token":"present"}`))
	req.Header.Set("Origin", "https://veritasvpn.cloud")
	rec = httptest.NewRecorder()
	unavailable(rec, req)
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("missing secret status = %d, want 503", rec.Code)
	}

	verified := check(&limiter{entries: map[string][]time.Time{}, limit: 10, window: time.Minute}, "secret", func(context.Context, string, string, string) error {
		return nil
	})
	req = httptest.NewRequest(http.MethodPost, "/api/v1/phishing/check", strings.NewReader(`{"url":"https://127.0.0.1","turnstile_token":"ok"}`))
	req.Header.Set("Origin", "https://veritasvpn.cloud")
	rec = httptest.NewRecorder()
	verified(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("verified private target status = %d, want 400", rec.Code)
	}
}

func TestSameOriginRequiresSite(t *testing.T) {
	allowed := []string{"https://veritasvpn.cloud", "https://www.veritasvpn.cloud"}
	for _, origin := range allowed {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/phishing/check", nil)
		req.Header.Set("Origin", origin)
		if !sameOrigin(req) {
			t.Fatalf("origin %s should be allowed", origin)
		}
	}
	rejected := []string{"", "https://evil.example", "http://veritasvpn.cloud", "https://veritasvpn.cloud.evil.example"}
	for _, origin := range rejected {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/phishing/check", nil)
		if origin != "" {
			req.Header.Set("Origin", origin)
		}
		if sameOrigin(req) {
			t.Fatalf("origin %q should be rejected", origin)
		}
	}
}

func TestBrandLookalike(t *testing.T) {
	if got := impersonatedBrand("paypal-account-check.example"); got != "PayPal" {
		t.Fatalf("got %q, want PayPal", got)
	}
	if got := impersonatedBrand("www.paypal.com"); got != "" {
		t.Fatalf("official domain was flagged as %q", got)
	}
}
