package service

import (
	"github.com/veritasvpn/services/billing-svc/internal/model"
	"testing"
)

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

func TestPlanCatalog(t *testing.T) {
	monthly, ok := model.PlanByID(model.PlanMonthly)
	if !ok || monthly.PriceCents != 300 || monthly.PeriodDays != 30 {
		t.Fatalf("unexpected monthly plan: %+v", monthly)
	}
	annual, ok := model.PlanByID(model.PlanAnnual)
	if !ok || annual.PriceCents != 3000 || annual.PeriodDays != 365 || annual.SavingsCents != 600 {
		t.Fatalf("unexpected annual plan: %+v", annual)
	}
}
