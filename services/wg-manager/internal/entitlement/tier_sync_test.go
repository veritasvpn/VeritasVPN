package entitlement

import (
	"testing"
	"time"

	"github.com/veritasvpn/lib/logging"
)

func testCache(t *testing.T) (*TierCache, *time.Time) {
	t.Helper()
	log, err := logging.New("error")
	if err != nil {
		t.Fatalf("logging.New: %v", err)
	}
	now := time.Now()
	c := NewTierCache(log)
	c.now = func() time.Time { return now }
	return c, &now
}

func TestLookupReturnsFreshEntry(t *testing.T) {
	c, _ := testCache(t)
	c.Set("acc_1", TierPremium)

	tier, ok := c.Lookup("acc_1")
	if !ok {
		t.Fatal("expected cache hit for a freshly stored tier")
	}
	if tier != TierPremium {
		t.Fatalf("tier = %q, want %q", tier, TierPremium)
	}
}

// A dropped subscription.expired event must not keep a paid tier alive forever.
func TestLookupTreatsExpiredEntryAsMiss(t *testing.T) {
	c, now := testCache(t)
	c.Set("acc_1", TierPremium)

	*now = now.Add(TierTTL + time.Second)

	if tier, ok := c.Lookup("acc_1"); ok {
		t.Fatalf("expected miss after TTL, got tier %q", tier)
	}
}

func TestLookupUnknownAccountIsMiss(t *testing.T) {
	c, _ := testCache(t)
	if _, ok := c.Lookup("nobody"); ok {
		t.Fatal("expected miss for an account that was never cached")
	}
}

func TestSetRefreshesExpiry(t *testing.T) {
	c, now := testCache(t)
	c.Set("acc_1", TierPremium)

	*now = now.Add(TierTTL - time.Second)
	c.Set("acc_1", TierPremium)
	*now = now.Add(TierTTL - time.Second)

	if _, ok := c.Lookup("acc_1"); !ok {
		t.Fatal("re-setting an entry should restart its TTL")
	}
}

func TestPruneDropsExpiredEntries(t *testing.T) {
	c, now := testCache(t)
	c.Set("stale", TierPremium)
	*now = now.Add(TierTTL + time.Second)
	c.Set("fresh", TierPremium)

	c.prune()

	c.mu.RLock()
	defer c.mu.RUnlock()
	if _, found := c.data["stale"]; found {
		t.Error("expired entry should have been pruned")
	}
	if _, found := c.data["fresh"]; !found {
		t.Error("prune must not drop entries inside their TTL")
	}
}

func TestSetNormalizesTier(t *testing.T) {
	c, _ := testCache(t)
	c.Set("acc_1", "  PREMIUM  ")

	tier, ok := c.Lookup("acc_1")
	if !ok {
		t.Fatal("expected cache hit")
	}
	if tier != NormalizeTier("PREMIUM") {
		t.Fatalf("tier = %q, want normalized value", tier)
	}
}
