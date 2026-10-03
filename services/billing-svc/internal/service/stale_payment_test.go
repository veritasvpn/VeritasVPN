package service

import (
	"testing"
	"time"

	"github.com/veritasvpn/services/billing-svc/internal/model"
)

func TestPendingPaymentOlderThanFourDaysIsStale(t *testing.T) {
	now := time.Date(2026, 10, 3, 22, 0, 0, 0, time.UTC)
	justOver := now.Add(-stalePendingPaymentAge - time.Second)
	exact := now.Add(-stalePendingPaymentAge)
	recent := now.Add(-3 * 24 * time.Hour)
	ancient := now.Add(-30 * 24 * time.Hour)

	if !paymentIsStalePending(model.PaymentPending, justOver, now) {
		t.Fatal("pending payment more than 4 days old should fail")
	}
	if paymentIsStalePending(model.PaymentPending, exact, now) {
		t.Fatal("pending payment exactly 4 days old is not more than 4 days")
	}
	if paymentIsStalePending(model.PaymentPending, recent, now) {
		t.Fatal("recent pending payment should stay pending")
	}
	if paymentIsStalePending(model.PaymentCompleted, ancient, now) {
		t.Fatal("confirmed payment must not be failed")
	}
	if paymentIsStalePending(model.PaymentRefunded, ancient, now) {
		t.Fatal("refunded payment must not be failed")
	}
	if paymentIsStalePending(model.PaymentFailed, ancient, now) {
		t.Fatal("already failed payment is not pending")
	}
}

func TestStalePendingCutoffIsFourDaysBeforeNow(t *testing.T) {
	now := time.Date(2026, 10, 7, 15, 4, 5, 0, time.FixedZone("PYT", -3*3600))
	cutoff := stalePendingCutoff(now)
	if !cutoff.Equal(now.UTC().Add(-4 * 24 * time.Hour)) {
		t.Fatalf("cutoff = %s", cutoff)
	}
	if cutoff.Location() != time.UTC {
		t.Fatal("cutoff should be UTC")
	}
}
