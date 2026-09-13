package handler

import "testing"

func TestCheckoutSuccessURLUsesOnlyTrustedReturnTargets(t *testing.T) {
	h := &BillingHandler{successURL: "https://veritasvpn.cloud/billing/success.html?campaign=bitcoin"}

	got, err := h.checkoutSuccessURL("android")
	if err != nil {
		t.Fatalf("android target: %v", err)
	}
	want := "https://veritasvpn.cloud/billing/app-return"
	if got != want {
		t.Fatalf("redirect = %q, want %q", got, want)
	}

	if _, err := h.checkoutSuccessURL("https://attacker.example"); err == nil {
		t.Fatal("expected arbitrary return target to be rejected")
	}
}
