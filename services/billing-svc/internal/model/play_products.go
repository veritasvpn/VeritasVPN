package model

// PlayProduct is one Google Play subscription the Play build can sell.
// Product IDs and base plan IDs are what the owner creates in Play Console.
// Prices and periods follow the existing premium plans. Play bills a calendar
// month or year; the BTCPay plans use 30 and 365 days.
type PlayProduct struct {
	PlanID        string
	ProductID     string
	BasePlanID    string
	PriceCents    int64
	PeriodDays    int
	BillingPeriod string
}

// PlayProducts is the only server-side catalog of Google Play subscription
// IDs. Keep it aligned with android/.../billing/PlayCatalog.kt.
var PlayProducts = []PlayProduct{
	{
		PlanID:        PlanMonthly,
		ProductID:     "premium_monthly",
		BasePlanID:    "monthly",
		PriceCents:    300,
		PeriodDays:    30,
		BillingPeriod: "monthly",
	},
	{
		PlanID:        PlanAnnual,
		ProductID:     "premium_annual",
		BasePlanID:    "yearly",
		PriceCents:    3000,
		PeriodDays:    365,
		BillingPeriod: "annual",
	},
}

func PlayProductByID(productOrPlan string) (PlayProduct, bool) {
	for _, product := range PlayProducts {
		if product.ProductID == productOrPlan || product.PlanID == productOrPlan {
			return product, true
		}
	}
	return PlayProduct{}, false
}
