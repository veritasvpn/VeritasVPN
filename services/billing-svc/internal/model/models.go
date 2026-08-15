package model

import "time"

const (
	TierFree    = "free"
	TierPremium = "premium"

	StatusActive   = "active"
	StatusCanceled = "canceled"
	StatusPastDue  = "past_due"
	StatusPending  = "pending"

	PaymentNone   = "none"
	PaymentBTCPay = "btcpay"
	PaymentStripe = "stripe"

	PaymentPending   = "pending"
	PaymentCompleted = "completed"
	PaymentFailed    = "failed"
	PaymentRefunded  = "refunded"
)

type Subscription struct {
	ID                 string    `json:"id"`
	AccountID          string    `json:"account_id"`
	Tier               string    `json:"tier"`
	Status             string    `json:"status"`
	PaymentMethod      string    `json:"payment_method"`
	CurrentPeriodStart time.Time `json:"current_period_start"`
	CurrentPeriodEnd   time.Time `json:"current_period_end"`
	CancelAtPeriodEnd  bool      `json:"cancel_at_period_end"`
	PlanID             string    `json:"plan_id"`
	BillingPeriod      string    `json:"billing_period"`
	PriceCents         int64     `json:"price_cents"`
	PeriodDays         int       `json:"period_days"`
	CreatedAt          time.Time `json:"created_at"`
	UpdatedAt          time.Time `json:"updated_at"`
}

type PaymentRecord struct {
	ID                    string    `json:"id"`
	SubscriptionID        string    `json:"subscription_id"`
	AccountID             string    `json:"account_id,omitempty"`
	Amount                int64     `json:"amount"`
	Currency              string    `json:"currency"`
	Status                string    `json:"status"`
	ProviderTransactionID string    `json:"provider_transaction_id"`
	CreatedAt             time.Time `json:"created_at"`
	PlanID                string    `json:"plan_id"`
	PeriodDays            int       `json:"period_days"`
}

// StatusResponse is returned by GET /billing/status.
type StatusResponse struct {
	AccountID          string     `json:"account_id"`
	Tier               string     `json:"tier"`
	Status             string     `json:"status"`
	PaymentMethod      string     `json:"payment_method"`
	CurrentPeriodStart *time.Time `json:"current_period_start,omitempty"`
	CurrentPeriodEnd   *time.Time `json:"current_period_end,omitempty"`
	CancelAtPeriodEnd  bool       `json:"cancel_at_period_end"`
	IsPremium          bool       `json:"is_premium"`
	PlanID             string     `json:"plan_id,omitempty"`
	BillingPeriod      string     `json:"billing_period,omitempty"`
	PriceCents         int64      `json:"price_cents,omitempty"`
	PeriodDays         int        `json:"period_days,omitempty"`
}
