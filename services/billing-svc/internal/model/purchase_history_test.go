package model

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestPurchaseHistoryFromRecordedPayments(t *testing.T) {
	created := time.Date(2026, 3, 2, 15, 4, 5, 0, time.FixedZone("PYT", -3*3600))
	items := PurchaseHistoryFrom([]PaymentRecord{
		{
			Amount:     3000,
			Currency:   "USD",
			Status:     PaymentCompleted,
			PlanID:     PlanAnnual,
			PeriodDays: 30,
			CreatedAt:  created,
		},
		{
			Amount:     300,
			Currency:   "usd",
			Status:     PaymentPending,
			PlanID:     PlanMonthly,
			PeriodDays: 365,
			CreatedAt:  created,
		},
		{
			Amount:     300,
			Currency:   "",
			Status:     PaymentFailed,
			PlanID:     "",
			PeriodDays: 30,
			CreatedAt:  created,
		},
		{
			Amount:     3000,
			Currency:   "usd",
			Status:     PaymentRefunded,
			PlanID:     "",
			PeriodDays: 365,
			CreatedAt:  created,
		},
		{
			Amount: 300,
			Status: "settled",
			PlanID: PlanMonthly,
		},
	})

	if len(items) != 5 {
		t.Fatalf("len = %d, want 5", len(items))
	}
	if items[0].Plan != "annual" || items[0].Status != PaymentCompleted || items[0].AmountCents != 3000 || items[0].Currency != "usd" {
		t.Fatalf("annual payment = %+v", items[0])
	}
	if !items[0].CreatedAt.Equal(created.UTC()) {
		t.Fatalf("created_at = %s, want UTC", items[0].CreatedAt)
	}
	if items[1].Plan != "monthly" || items[1].Status != PaymentPending || items[1].AmountCents != 300 {
		t.Fatalf("monthly payment = %+v", items[1])
	}
	if items[2].Plan != "monthly" || items[2].Currency != "usd" || items[2].Status != PaymentFailed {
		t.Fatalf("defaulted payment = %+v", items[2])
	}
	if items[3].Plan != "annual" || items[3].Status != PaymentRefunded {
		t.Fatalf("period fallback = %+v", items[3])
	}
	if items[4].Status != PaymentPending {
		t.Fatalf("unknown status = %q, want pending", items[4].Status)
	}
}

func TestPurchaseHistoryOmitsPaymentReferences(t *testing.T) {
	const secret = "fake-txid"
	items := PurchaseHistoryFrom([]PaymentRecord{{
		ID:                    "payment-row",
		SubscriptionID:        "subscription-row",
		AccountID:             "account-row",
		Amount:                300,
		Currency:              "usd",
		Status:                PaymentCompleted,
		ProviderTransactionID: secret,
		PlanID:                PlanMonthly,
		PeriodDays:            30,
		CreatedAt:             time.Date(2026, 1, 15, 0, 0, 0, 0, time.UTC),
	}})

	raw, err := json.Marshal(items)
	if err != nil {
		t.Fatal(err)
	}
	body := string(raw)
	for _, leaked := range []string{secret, "payment-row", "subscription-row", "account-row", "provider_transaction_id", "invoice", "address", "fake-txid"} {
		if strings.Contains(body, leaked) {
			t.Fatalf("history leaked %q: %s", leaked, body)
		}
	}
	var decoded []map[string]interface{}
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatal(err)
	}
	if len(decoded) != 1 {
		t.Fatalf("decoded len = %d", len(decoded))
	}
	for key := range decoded[0] {
		switch key {
		case "created_at", "amount_cents", "currency", "plan", "status", "provider":
		default:
			t.Fatalf("unexpected history field %q", key)
		}
	}
	if decoded[0]["provider"] != "btcpay" {
		t.Fatalf("provider = %v, want btcpay", decoded[0]["provider"])
	}
}

func TestPurchaseHistoryEmptyIsAnEmptyList(t *testing.T) {
	raw, err := json.Marshal(PurchaseHistoryFrom(nil))
	if err != nil {
		t.Fatal(err)
	}
	if string(raw) != "[]" {
		t.Fatalf("empty history = %s, want []", raw)
	}
}
