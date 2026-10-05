package entitlement

import (
	"strings"
	"testing"
)

func TestParseShieldFlagsRejectsCategoryLists(t *testing.T) {
	_, err := ParseShieldFlags([]byte(`{"block_malicious":true,"block_ads":false,"block_adult":false,"categories":["adult"]}`))
	if err == nil || !strings.Contains(err.Error(), "block_malicious") {
		t.Fatalf("expected category list to be rejected, got %v", err)
	}
}

func TestParseShieldFlagsRequiresAllThree(t *testing.T) {
	_, err := ParseShieldFlags([]byte(`{"block_malicious":true}`))
	if err == nil {
		t.Fatal("expected missing toggles to fail")
	}
	flags, err := ParseShieldFlags([]byte(`{"block_malicious":false,"block_ads":true,"block_adult":true}`))
	if err != nil {
		t.Fatal(err)
	}
	if flags.BlockMalicious || !flags.BlockAds || !flags.BlockAdult {
		t.Fatalf("unexpected flags: %+v", flags)
	}
}

func TestEffectiveFlagsAndAlias(t *testing.T) {
	fromPreset := EffectiveFlags(false, false, false, false, ShieldPresetAggressive)
	if !fromPreset.BlockMalicious || !fromPreset.BlockAds || fromPreset.BlockAdult {
		t.Fatalf("aggressive preset view: %+v", fromPreset)
	}
	standard := EffectiveFlags(false, true, true, true, ShieldPresetStandard)
	if standard != DefaultShieldFlags() {
		t.Fatalf("unset flags must ignore the raw columns and use Standard, got %+v", standard)
	}
	explicit := EffectiveFlags(true, false, true, true, ShieldPresetStandard)
	if explicit.BlockMalicious || !explicit.BlockAds || !explicit.BlockAdult {
		t.Fatalf("explicit flags must win: %+v", explicit)
	}
	if AliasPreset(DefaultShieldFlags()) != ShieldPresetStandard {
		t.Fatal("default flags alias to standard")
	}
	if AliasPreset(ShieldFlags{BlockMalicious: true, BlockAds: true}) != ShieldPresetAggressive {
		t.Fatal("ads on aliases to aggressive for older agents")
	}
	if AliasPreset(ShieldFlags{BlockAdult: true}) != ShieldPresetStandard {
		t.Fatal("adult-only still aliases to standard; new agents read the flags")
	}
}

func TestShieldEntitlement(t *testing.T) {
	if err := CheckShieldUpdate(TierFree); err == nil {
		t.Fatal("free accounts cannot set shield toggles")
	} else if pe, ok := err.(*PlanError); !ok || pe.Code != "subscription_required" {
		t.Fatalf("unexpected error: %#v", err)
	}
	if err := CheckShieldUpdate(TierPremium); err != nil {
		t.Fatal(err)
	}
	if err := CheckShieldUpdate(""); err == nil {
		t.Fatal("unknown tier is free and cannot enable shield toggles")
	}
	if err := CheckShieldPreset(TierFree, ShieldPresetAggressive); err == nil {
		t.Fatal("aggressive enables ads and requires Premium")
	}
	if err := CheckShieldPreset(TierFree, ShieldPresetStandard); err != nil {
		t.Fatal(err)
	}
	if err := CheckShieldPreset(TierPremium, ShieldPresetAggressive); err != nil {
		t.Fatal(err)
	}
}
