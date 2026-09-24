package service

import (
	"testing"
	"time"

	"github.com/veritasvpn/services/auth-svc/internal/model"
)

func TestEffectiveSubscriptionTier(t *testing.T) {
	now := time.Date(2026, 9, 24, 15, 0, 0, 0, time.UTC)
	future := now.Add(time.Hour)
	past := now.Add(-time.Hour)

	cases := []struct {
		name string
		acc  *model.Account
		want string
	}{
		{name: "missing account", acc: nil, want: "free"},
		{name: "free row", acc: &model.Account{SubscriptionTier: "free"}, want: "free"},
		{name: "premium without expiry", acc: &model.Account{SubscriptionTier: "premium"}, want: "premium"},
		{name: "premium until later", acc: &model.Account{SubscriptionTier: "Premium", SubscriptionExpiry: &future}, want: "premium"},
		{name: "premium already expired", acc: &model.Account{SubscriptionTier: "premium", SubscriptionExpiry: &past}, want: "free"},
		{name: "expiry exactly now", acc: &model.Account{SubscriptionTier: "premium", SubscriptionExpiry: &now}, want: "free"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := EffectiveSubscriptionTier(tc.acc, now); got != tc.want {
				t.Fatalf("EffectiveSubscriptionTier() = %q, want %q", got, tc.want)
			}
		})
	}
}
