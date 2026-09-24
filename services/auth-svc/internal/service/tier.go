package service

import (
	"strings"
	"time"

	"github.com/veritasvpn/services/auth-svc/internal/model"
)

// EffectiveSubscriptionTier is the accounts row, not the tier claim inside an
// access token. Callers that gate a paid feature (the browser proxy validate
// route) must use this so a token minted before cancellation cannot keep
// premium access until it expires. A premium row whose expiry is already past
// is free, which covers a missed subscription.expired event. A premium row
// with no expiry is still premium: that matches how the rest of the stack
// reads subscription_tier.
func EffectiveSubscriptionTier(acc *model.Account, now time.Time) string {
	if acc == nil {
		return "free"
	}
	if !strings.EqualFold(strings.TrimSpace(acc.SubscriptionTier), "premium") {
		return "free"
	}
	if acc.SubscriptionExpiry != nil && !acc.SubscriptionExpiry.After(now) {
		return "free"
	}
	return "premium"
}
