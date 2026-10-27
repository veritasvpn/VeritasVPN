package repository

import (
	"errors"
	"fmt"
	"testing"

	"github.com/jackc/pgx/v5/pgconn"
	"github.com/veritasvpn/services/billing-svc/internal/model"
)

func TestPlayPaymentUniqueConflictDoesNotMatchOtherSQLErrors(t *testing.T) {
	conflict := fmt.Errorf("update google play payment: %w", &pgconn.PgError{
		Code:           "23505",
		ConstraintName: playProviderTxnUniqueIndex,
	})
	if !IsPlayPaymentUniqueConflict(conflict) {
		t.Fatal("duplicate provider_transaction_id was not recognized")
	}
	otherUnique := fmt.Errorf("update subscription: %w", &pgconn.PgError{
		Code:           "23505",
		ConstraintName: "subscriptions_account_id_key",
	})
	if IsPlayPaymentUniqueConflict(otherUnique) {
		t.Fatal("a different unique constraint was acknowledged")
	}
	unnamed := fmt.Errorf("update google play payment: %w", &pgconn.PgError{Code: "23505"})
	if IsPlayPaymentUniqueConflict(unnamed) {
		t.Fatal("a unique violation without the payment index was acknowledged")
	}
	fk := fmt.Errorf("update google play payment: %w", &pgconn.PgError{Code: "23503"})
	if IsPlayPaymentUniqueConflict(fk) {
		t.Fatal("foreign key was treated as a duplicate order id")
	}
	if IsPlayPaymentUniqueConflict(errors.New("database unavailable")) {
		t.Fatal("plain error was treated as a duplicate order id")
	}
}

func TestSamePlayChargeDropsOnlyTheMatchingLegacyRow(t *testing.T) {
	legacy := model.PaymentRecord{
		ID:                    "pay-legacy",
		AccountID:             "acc-1",
		Amount:                2_000_000,
		Currency:              "pyg",
		Status:                model.PaymentCompleted,
		ProviderTransactionID: "token-1",
		PlanID:                "premium_monthly",
		IsTrial:               false,
	}
	incoming := legacy
	incoming.ProviderTransactionID = "GPA.1111..0"
	incoming.PurchaseToken = "token-1"
	incoming.Currency = "PYG"
	if !samePlayCharge(legacy, incoming) {
		t.Fatal("same charge was kept as a second history line")
	}
	differentAmount := incoming
	differentAmount.Amount = 300
	if samePlayCharge(legacy, differentAmount) {
		t.Fatal("different amount was treated as the same charge")
	}
	refunded := incoming
	refunded.Status = model.PaymentRefunded
	if samePlayCharge(legacy, refunded) {
		t.Fatal("refunded write would delete a completed legacy charge")
	}
	trial := incoming
	trial.IsTrial = true
	if samePlayCharge(legacy, trial) {
		t.Fatal("trial flag mismatch was treated as the same charge")
	}
	otherPlan := incoming
	otherPlan.PlanID = "premium_annual"
	if samePlayCharge(legacy, otherPlan) {
		t.Fatal("different plan was treated as the same charge")
	}
	blankPlan := incoming
	blankPlan.PlanID = ""
	if !samePlayCharge(legacy, blankPlan) {
		t.Fatal("a blank incoming plan should still match the stored charge")
	}
	otherAccount := incoming
	otherAccount.AccountID = "acc-2"
	if samePlayCharge(legacy, otherAccount) {
		t.Fatal("different account was treated as the same charge")
	}
	alreadyOrder := legacy
	alreadyOrder.ProviderTransactionID = incoming.ProviderTransactionID
	if samePlayCharge(alreadyOrder, incoming) {
		t.Fatal("order-id row would be deleted")
	}
}
