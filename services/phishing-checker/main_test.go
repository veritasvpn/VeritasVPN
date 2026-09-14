package main

import (
	"context"
	"net"
	"testing"
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

func TestBrandLookalike(t *testing.T) {
	if got := impersonatedBrand("paypal-account-check.example"); got != "PayPal" {
		t.Fatalf("got %q, want PayPal", got)
	}
	if got := impersonatedBrand("www.paypal.com"); got != "" {
		t.Fatalf("official domain was flagged as %q", got)
	}
}
