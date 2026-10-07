package model

import "testing"

func TestPlayProductsMatchPlanPrices(t *testing.T) {
	if len(PlayProducts) != 2 {
		t.Fatalf("products = %d", len(PlayProducts))
	}
	monthly, ok := PlayProductByID("premium_monthly")
	if !ok || monthly.BasePlanID != "monthly" || monthly.PriceCents != 300 || monthly.PeriodDays != 30 {
		t.Fatalf("monthly = %+v ok=%v", monthly, ok)
	}
	annual, ok := PlayProductByID("premium_annual")
	if !ok || annual.BasePlanID != "yearly" || annual.PriceCents != 3000 || annual.PeriodDays != 365 {
		t.Fatalf("annual = %+v ok=%v", annual, ok)
	}
	for _, product := range PlayProducts {
		plan, ok := PlanByID(product.PlanID)
		if !ok {
			t.Fatalf("missing plan %s", product.PlanID)
		}
		if plan.PriceCents != product.PriceCents || plan.PeriodDays != product.PeriodDays {
			t.Fatalf("play product %+v does not match plan %+v", product, plan)
		}
	}
}

func TestPurchaseHistoryIncludesGooglePlayProvider(t *testing.T) {
	items := PurchaseHistoryFrom([]PaymentRecord{{
		Amount:                300,
		Currency:              "usd",
		Status:                PaymentCompleted,
		Provider:              PaymentGooglePlay,
		ProviderTransactionID: "super-secret-token",
		PlanID:                PlanMonthly,
		PeriodDays:            30,
	}})
	if len(items) != 1 || items[0].Provider != PaymentGooglePlay || items[0].Plan != "monthly" {
		t.Fatalf("item = %+v", items)
	}
}
