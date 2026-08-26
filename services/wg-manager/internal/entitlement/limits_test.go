package entitlement

import "testing"

func TestNormalizeTier(t *testing.T) {
	if got := NormalizeTier(""); got != TierFree {
		t.Fatalf("empty -> free, got %q", got)
	}
	if got := NormalizeTier("PREMIUM"); got != TierPremium {
		t.Fatalf("PREMIUM -> premium, got %q", got)
	}
	if got := NormalizeTier("unknown"); got != TierFree {
		t.Fatalf("unknown -> free, got %q", got)
	}
}

func TestCheckCreatePeerRequiresPaidSubscription(t *testing.T) {
	err := CheckCreatePeer(TierFree, 0, "", nil)
	pe, ok := err.(*PlanError)
	if !ok || pe.Code != "subscription_required" {
		t.Fatalf("unexpected error: %#v", err)
	}

	if err := CheckCreatePeer(TierPremium, 4, "", nil); err != nil {
		t.Fatalf("premium under limit: %v", err)
	}
	if err := CheckCreatePeer(TierPremium, 5, "", nil); err == nil {
		t.Fatal("expected premium device limit")
	}
}

func TestPremiumIgnoresLegacyFreeRegionList(t *testing.T) {
	allowed := []string{"local"}
	if err := CheckCreatePeer(TierPremium, 0, "eu-west", allowed); err != nil {
		t.Fatalf("premium ignores legacy allow-list: %v", err)
	}
}

func TestCheckSelectedRegion(t *testing.T) {
	allowed := []string{"local"}
	if err := CheckSelectedRegion(TierFree, "ams", allowed); err == nil {
		t.Fatal("expected deny")
	}
	if err := CheckSelectedRegion(TierFree, "local", allowed); err != nil {
		t.Fatal(err)
	}
	if err := CheckSelectedRegion(TierFree, "ams", nil); err != nil {
		t.Fatal("empty allow-list should allow any")
	}
}

func TestCheckCreatePortForward(t *testing.T) {
	if err := CheckCreatePortForward(TierFree, 0); err == nil {
		t.Fatal("expected subscription required")
	}
	if err := CheckCreatePortForward(TierPremium, 0); err != nil {
		t.Fatal(err)
	}
	if err := CheckCreatePortForward(TierPremium, 2); err == nil {
		t.Fatal("expected port-forward limit")
	}
}

func TestValidateExternalPort(t *testing.T) {
	if err := ValidateExternalPort(40000); err != nil {
		t.Fatal(err)
	}
	if err := ValidateExternalPort(51820); err == nil {
		t.Fatal("expected reserved")
	}
	if err := ValidateExternalPort(20050); err == nil {
		t.Fatal("expected reserved range")
	}
	if err := ValidateExternalPort(80); err == nil {
		t.Fatal("expected reserved")
	}
}
