package model

type Plan struct {
	ID            string `json:"id"`
	Name          string `json:"name"`
	BillingPeriod string `json:"billing_period"`
	PriceCents    int64  `json:"price_cents"`
	PeriodDays    int    `json:"period_days"`
	SavingsCents  int64  `json:"savings_cents"`
}

const (
	PlanMonthly = "premium_monthly"
	PlanAnnual  = "premium_annual"
)

var plans = []Plan{
	{ID: PlanMonthly, Name: "Monthly", BillingPeriod: "monthly", PriceCents: 300, PeriodDays: 30},
	{ID: PlanAnnual, Name: "Annual", BillingPeriod: "annual", PriceCents: 3000, PeriodDays: 365, SavingsCents: 600},
}

func Plans() []Plan { out := make([]Plan, len(plans)); copy(out, plans); return out }
func PlanByID(id string) (Plan, bool) {
	if id == "" {
		id = PlanMonthly
	}
	for _, p := range plans {
		if p.ID == id {
			return p, true
		}
	}
	return Plan{}, false
}
