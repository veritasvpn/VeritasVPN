package service

import "testing"

func TestPremiumDefaults(t *testing.T) {
	s := &BillingService{cfg: BillingConfig{}}
	if s.PremiumAmountCents() != 300 {
		t.Fatalf("expected 300 cents, got %d", s.PremiumAmountCents())
	}
	if s.periodDuration().Hours() != 30*24 {
		t.Fatalf("expected 30 days period")
	}

	s.cfg.PremiumPriceUSDCents = 300
	s.cfg.PremiumPeriodDays = 30
	if s.PremiumAmountCents() != 300 {
		t.Fatalf("expected configured 300")
	}
}
