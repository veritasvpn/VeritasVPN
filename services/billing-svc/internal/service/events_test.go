package service

import (
	"encoding/json"
	"testing"
	"time"
)

func TestRenewedEventPayloadIncludesAccountIDAndPeriodEnd(t *testing.T) {
	end := time.Date(2026, 9, 1, 12, 0, 0, 0, time.UTC)
	payload := renewedEventPayload("acc-test-1", "premium", "btcpay", "monthly", 30, end)

	if payload["account_id"] != "acc-test-1" {
		t.Fatalf("account_id=%v", payload["account_id"])
	}
	if payload["tier"] != "premium" {
		t.Fatalf("tier=%v", payload["tier"])
	}
	gotEnd, ok := payload["period_end"].(time.Time)
	if !ok || !gotEnd.Equal(end) {
		t.Fatalf("period_end=%v ok=%v", payload["period_end"], ok)
	}

	// Ensure JSON shape matches auth-svc SubscriptionEvent expectations.
	raw, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	var decoded struct {
		AccountID string     `json:"account_id"`
		Tier      string     `json:"tier"`
		PeriodEnd *time.Time `json:"period_end"`
	}
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.AccountID == "" || decoded.PeriodEnd == nil || !decoded.PeriodEnd.Equal(end) {
		t.Fatalf("decoded=%+v", decoded)
	}
}
