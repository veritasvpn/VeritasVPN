package entitlement

import (
	"strings"
	"testing"
)

func TestParseShieldFlagsRejectsCategoryLists(t *testing.T) {
	_, err := ParseShieldFlags([]byte(`{"block_malicious":true,"block_ads":false,"block_adult":false,"block_trackers":true,"categories":["trackers","ads"]}`))
	if err == nil || !strings.Contains(err.Error(), "block_malicious") {
		t.Fatalf("expected category list to be rejected, got %v", err)
	}
	_, err = ParseShieldFlags([]byte(`{"block_malicious":true,"block_ads":false,"block_adult":false,"trackers":true}`))
	if err == nil {
		t.Fatal("expected a raw category name to be rejected")
	}
}

func TestParseShieldFlagsRequiresCoreToggles(t *testing.T) {
	_, err := ParseShieldFlags([]byte(`{"block_malicious":true}`))
	if err == nil {
		t.Fatal("expected missing toggles to fail")
	}
	flags, err := ParseShieldFlags([]byte(`{"block_malicious":false,"block_ads":true,"block_adult":true,"block_trackers":false}`))
	if err != nil {
		t.Fatal(err)
	}
	if flags.BlockMalicious || !flags.BlockAds || !flags.BlockAdult || flags.BlockTrackers {
		t.Fatalf("unexpected flags: %+v", flags)
	}
}

func TestParseShieldFlagsLegacyThreeFlagsKeepTrackersOn(t *testing.T) {
	flags, err := ParseShieldFlags([]byte(`{"block_malicious":true,"block_ads":false,"block_adult":false}`))
	if err != nil {
		t.Fatal(err)
	}
	if !flags.BlockMalicious || flags.BlockAds || flags.BlockAdult || !flags.BlockTrackers {
		t.Fatalf("omitted block_trackers must stay on: %+v", flags)
	}
}

func TestCategoriesForFlagsDoNotMergeTrackersIntoAds(t *testing.T) {
	trackersOnly := CategoriesForFlags(ShieldFlags{BlockTrackers: true})
	if !hasCategory(trackersOnly, CategoryTrackers) || hasCategory(trackersOnly, CategoryAds) || hasCategory(trackersOnly, CategoryMalware) {
		t.Fatalf("trackers toggle must enable only trackers: %v", trackersOnly)
	}
	adsOnly := CategoriesForFlags(ShieldFlags{BlockAds: true})
	if !hasCategory(adsOnly, CategoryAds) || hasCategory(adsOnly, CategoryTrackers) {
		t.Fatalf("ads toggle must not enable trackers: %v", adsOnly)
	}
	def := CategoriesForFlags(DefaultShieldFlags())
	for _, want := range []string{CategoryMalware, CategoryPhishing, CategoryScam, CategoryCrypto, CategoryTrackers} {
		if !hasCategory(def, want) {
			t.Fatalf("default flags missing %s: %v", want, def)
		}
	}
	if hasCategory(def, CategoryAds) || hasCategory(def, CategoryAdult) {
		t.Fatalf("default flags should leave ads and adult off: %v", def)
	}
	off := CategoriesForFlags(ShieldFlags{BlockMalicious: true, BlockAds: true, BlockAdult: true, BlockTrackers: false})
	if hasCategory(off, CategoryTrackers) || !hasCategory(off, CategoryAds) || !hasCategory(off, CategoryAdult) {
		t.Fatalf("trackers off must drop only the trackers category: %v", off)
	}
}

func TestEffectiveFlagsAndAlias(t *testing.T) {
	fromPreset := EffectiveFlags(false, false, false, false, false, ShieldPresetAggressive)
	if !fromPreset.BlockMalicious || !fromPreset.BlockAds || fromPreset.BlockAdult || !fromPreset.BlockTrackers {
		t.Fatalf("aggressive preset view: %+v", fromPreset)
	}
	standard := EffectiveFlags(false, true, true, true, false, ShieldPresetStandard)
	if standard != DefaultShieldFlags() {
		t.Fatalf("unset flags must ignore the raw columns and use Standard, got %+v", standard)
	}
	security := EffectiveFlags(false, false, false, false, true, ShieldPresetSecurity)
	if !security.BlockMalicious || security.BlockAds || security.BlockAdult || security.BlockTrackers {
		t.Fatalf("security preset view must leave trackers off: %+v", security)
	}
	explicit := EffectiveFlags(true, false, true, true, false, ShieldPresetStandard)
	if explicit.BlockMalicious || !explicit.BlockAds || !explicit.BlockAdult || explicit.BlockTrackers {
		t.Fatalf("explicit flags must win: %+v", explicit)
	}
	if AliasPreset(DefaultShieldFlags()) != ShieldPresetStandard {
		t.Fatal("default flags alias to standard")
	}
	if AliasPreset(ShieldFlags{BlockMalicious: true, BlockAds: true, BlockTrackers: false}) != ShieldPresetAggressive {
		t.Fatal("ads on aliases to aggressive for older agents")
	}
	if AliasPreset(ShieldFlags{BlockAdult: true, BlockTrackers: false}) != ShieldPresetStandard {
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

func hasCategory(categories []string, want string) bool {
	for _, category := range categories {
		if category == want {
			return true
		}
	}
	return false
}
