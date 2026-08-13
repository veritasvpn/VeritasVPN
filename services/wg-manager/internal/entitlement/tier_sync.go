package entitlement

import (
	"encoding/json"
	"sync"
	"time"

	"github.com/nats-io/nats.go"
	"github.com/veritasvpn/lib/logging"
	"go.uber.org/zap"
)

type BillingEvent struct {
	AccountID string     `json:"account_id"`
	Tier      string     `json:"tier"`
	PeriodEnd *time.Time `json:"period_end"`
}

type TierCache struct {
	mu   sync.RWMutex
	data map[string]string // accountID -> tier
	log  *logging.Logger
}

func NewTierCache(log *logging.Logger) *TierCache {
	return &TierCache{
		data: make(map[string]string),
		log:  log,
	}
}

func (c *TierCache) Lookup(accountID string) (tier string, ok bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	tier, ok = c.data[accountID]
	return
}

func (c *TierCache) Set(accountID, tier string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.data[accountID] = NormalizeTier(tier)
}

func (c *TierCache) StartSync(nc *nats.Conn) error {
	if nc == nil {
		return nil
	}

	handler := func(msg *nats.Msg) {
		var ev BillingEvent
		if err := json.Unmarshal(msg.Data, &ev); err != nil {
			c.log.Warn("tier sync: unmarshal failed", zap.Error(err), zap.String("subject", msg.Subject))
			return
		}
		if ev.AccountID == "" {
			return
		}

		var tier string
		switch msg.Subject {
		case "subscription.renewed":
			tier = ev.Tier
			if tier == "" {
				tier = TierPremium
			}
		case "subscription.expired":
			tier = TierFree
		default:
			return
		}

		c.Set(ev.AccountID, tier)
		c.log.Debug("tier cache updated",
			zap.String("account_id", ev.AccountID),
			zap.String("tier", tier),
			zap.String("subject", msg.Subject),
		)
	}

	for _, subject := range []string{"subscription.renewed", "subscription.expired"} {
		if _, err := nc.Subscribe(subject, handler); err != nil {
			return err
		}
	}
	c.log.Info("tier sync started (listening for billing events)")
	return nil
}
