package service

import (
	"encoding/json"
	"testing"
	"time"
)

func TestSubscriptionEventJSON(t *testing.T) {
	end := time.Date(2026, 8, 28, 12, 0, 0, 0, time.UTC)
	raw, err := json.Marshal(map[string]interface{}{
		"account_id": "acc_1",
		"tier":       "premium",
		"period_end": end,
	})
	if err != nil {
		t.Fatal(err)
	}
	var ev SubscriptionEvent
	if err := json.Unmarshal(raw, &ev); err != nil {
		t.Fatal(err)
	}
	if ev.AccountID != "acc_1" || ev.Tier != "premium" || ev.PeriodEnd == nil {
		t.Fatalf("unexpected event: %+v", ev)
	}
	if !ev.PeriodEnd.Equal(end) {
		t.Fatalf("period_end mismatch: %v vs %v", ev.PeriodEnd, end)
	}
}
