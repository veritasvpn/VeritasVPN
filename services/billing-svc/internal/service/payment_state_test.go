package service

import (
	"testing"

	"github.com/veritasvpn/services/billing-svc/internal/model"
)

func TestPaymentStatePresentationIsSafeAndActionable(t *testing.T) {
	cases := []struct {
		state string
		poll  int
		want  string
	}{
		{model.PaymentStateNone, 0, ""},
		{model.PaymentStateAwaitingPayment, 15, "Waiting for your Bitcoin payment."},
		{model.PaymentStateAwaitingConfirmation, 3, "Payment received. Premium activates after the required Bitcoin confirmation."},
		{model.PaymentStateChecking, 3, "Checking your Bitcoin payment status."},
		{model.PaymentStateSettled, 0, "Payment confirmed. Premium is active."},
		{model.PaymentStateFailed, 0, "This checkout was not confirmed. Start a new checkout to try again."},
	}

	for _, tc := range cases {
		t.Run(tc.state, func(t *testing.T) {
			if got := paymentStatePollAfter(tc.state); got != tc.poll {
				t.Fatalf("poll interval = %d, want %d", got, tc.poll)
			}
			if got := paymentStateMessage(tc.state); got != tc.want {
				t.Fatalf("message = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestPaymentStateDoesNotExposePaymentIdentifiers(t *testing.T) {
	for _, state := range []string{
		model.PaymentStateAwaitingPayment,
		model.PaymentStateAwaitingConfirmation,
		model.PaymentStateChecking,
		model.PaymentStateSettled,
		model.PaymentStateFailed,
	} {
		if message := paymentStateMessage(state); message == "" {
			t.Fatalf("state %q must have a user-facing message", state)
		}
	}
}
