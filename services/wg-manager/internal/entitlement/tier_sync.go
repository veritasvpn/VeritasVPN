package entitlement

import (
	"context"
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

// TierTTL bounds how long a NATS-derived tier is trusted. The cache is a
// latency optimisation over the database, which is authoritative; an entry that
// outlives its TTL falls back to a database read. Without this, a
// subscription.expired event dropped during a NATS or wg-manager restart would
// leave a paid tier cached for the lifetime of the process.
const TierTTL = 10 * time.Minute

type tierEntry struct {
	tier     string
	storedAt time.Time
}

type TierCache struct {
	mu   sync.RWMutex
	data map[string]tierEntry // accountID -> tier
	now  func() time.Time     // overridable in tests
	log  *logging.Logger
}

func NewTierCache(log *logging.Logger) *TierCache {
	return &TierCache{
		data: make(map[string]tierEntry),
		now:  time.Now,
		log:  log,
	}
}

func (c *TierCache) Lookup(accountID string) (tier string, ok bool) {
	c.mu.RLock()
	entry, found := c.data[accountID]
	c.mu.RUnlock()
	if !found || c.now().Sub(entry.storedAt) >= TierTTL {
		return "", false
	}
	return entry.tier, true
}

func (c *TierCache) Set(accountID, tier string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.data[accountID] = tierEntry{tier: NormalizeTier(tier), storedAt: c.now()}
}

// prune drops expired entries so the map does not grow without bound for the
// lifetime of the process.
func (c *TierCache) prune() {
	cutoff := c.now().Add(-TierTTL)
	c.mu.Lock()
	defer c.mu.Unlock()
	for id, entry := range c.data {
		if entry.storedAt.Before(cutoff) {
			delete(c.data, id)
		}
	}
}

// StartPruning reclaims expired entries until ctx is cancelled.
func (c *TierCache) StartPruning(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(TierTTL)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				c.prune()
			}
		}
	}()
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
			zap.String("account_hash", logging.HashIdentifier(ev.AccountID)),
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
