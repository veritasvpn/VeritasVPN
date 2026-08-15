package service

import (
	"context"
	"encoding/json"
	"time"

	"github.com/nats-io/nats.go"
	"github.com/veritasvpn/lib/logging"
	"go.uber.org/zap"
)

// SubscriptionEvent is published by billing-svc on renew/expire/cancel.
type SubscriptionEvent struct {
	AccountID string     `json:"account_id"`
	Tier      string     `json:"tier"`
	PeriodEnd *time.Time `json:"period_end"`
}

// StartSubscriptionSync listens for billing NATS events and updates
// accounts.subscription_tier so JWTs issued after refresh reflect Premium.
func (s *AuthService) StartSubscriptionSync(nc *nats.Conn, log *logging.Logger) error {
	if nc == nil {
		return nil
	}

	handler := func(msg *nats.Msg) {
		var ev SubscriptionEvent
		if err := json.Unmarshal(msg.Data, &ev); err != nil {
			log.Warn("subscription event unmarshal failed", zap.Error(err), zap.String("subject", msg.Subject))
			return
		}
		if ev.AccountID == "" {
			return
		}

		tier := ev.Tier
		var expiry *time.Time
		switch msg.Subject {
		case "subscription.renewed":
			if tier == "" {
				tier = "premium"
			}
			if ev.PeriodEnd != nil {
				expiry = ev.PeriodEnd
			}
		case "subscription.expired":
			tier = "free"
			expiry = nil
		case "subscription.canceled":
			// Cancel-at-period-end keeps premium until expire worker runs.
			return
		default:
			return
		}

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := s.db.UpdateAccountTier(ctx, ev.AccountID, tier, expiry); err != nil {
			log.Error("failed to sync account tier from billing",
				zap.String("account_hash", logging.HashIdentifier(ev.AccountID)),
				zap.String("tier", tier),
				zap.String("subject", msg.Subject),
				zap.Error(err),
			)
			return
		}
		log.Info("synced account tier from billing",
			zap.String("account_hash", logging.HashIdentifier(ev.AccountID)),
			zap.String("tier", tier),
			zap.String("subject", msg.Subject),
		)
	}

	for _, subject := range []string{"subscription.renewed", "subscription.expired", "subscription.canceled"} {
		if _, err := nc.Subscribe(subject, handler); err != nil {
			return err
		}
	}
	return nil
}
