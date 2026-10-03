package model

import "strings"

const purchaseHistoryPlanAnnualDays = 300

// PurchaseHistoryFrom maps stored payment rows into the account-facing
// history. It keeps the recorded amount and confirmation status, and classifies
// the plan as monthly or annual. Payment references are not copied.
func PurchaseHistoryFrom(records []PaymentRecord) []PurchaseHistoryItem {
	out := make([]PurchaseHistoryItem, 0, len(records))
	for _, record := range records {
		out = append(out, PurchaseHistoryItem{
			CreatedAt:   record.CreatedAt.UTC(),
			AmountCents: record.Amount,
			Currency:    purchaseCurrency(record.Currency),
			Plan:        purchasePlan(record),
			Status:      purchaseStatus(record.Status),
		})
	}
	return out
}

func purchaseCurrency(currency string) string {
	currency = strings.ToLower(strings.TrimSpace(currency))
	if currency == "" {
		return "usd"
	}
	return currency
}

func purchasePlan(record PaymentRecord) string {
	switch record.PlanID {
	case PlanAnnual:
		return "annual"
	case PlanMonthly:
		return "monthly"
	}
	if record.PeriodDays >= purchaseHistoryPlanAnnualDays {
		return "annual"
	}
	return "monthly"
}

func purchaseStatus(status string) string {
	switch status {
	case PaymentCompleted, PaymentPending, PaymentFailed, PaymentRefunded:
		return status
	default:
		// An unrecognized stored status must not be presented as confirmed.
		return PaymentPending
	}
}
