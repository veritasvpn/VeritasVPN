package service

import (
	"testing"

	"github.com/veritasvpn/services/wg-manager/internal/entitlement"
	"github.com/veritasvpn/services/wg-manager/internal/model"
)

func TestResolveShieldForCreateKeepsStoredPreset(t *testing.T) {
	replaced := &model.Peer{ShieldPreset: entitlement.ShieldPresetSecurity}
	preset, set, malicious, ads, adult := resolveShieldForCreate("", nil, replaced)
	if preset != entitlement.ShieldPresetSecurity || set || malicious || ads || adult {
		t.Fatalf("missing flags must keep the stored preset, got %s set=%v flags=%v %v %v", preset, set, malicious, ads, adult)
	}
}

func TestResolveShieldForCreateExplicitFlags(t *testing.T) {
	flags := entitlement.ShieldFlags{BlockMalicious: true, BlockAds: false, BlockAdult: true}
	preset, set, malicious, ads, adult := resolveShieldForCreate(entitlement.ShieldPresetAggressive, &flags, &model.Peer{ShieldPreset: entitlement.ShieldPresetAggressive, ShieldPolicySet: true, ShieldBlockAds: true})
	if !set || !malicious || ads || !adult {
		t.Fatalf("explicit flags: set=%v %v %v %v", set, malicious, ads, adult)
	}
	if preset != entitlement.ShieldPresetStandard {
		t.Fatalf("adult without ads aliases to standard for old agents, got %s", preset)
	}
}

func TestResolveShieldForCreateLegacyPresetClearsFlags(t *testing.T) {
	replaced := &model.Peer{ShieldPreset: entitlement.ShieldPresetStandard, ShieldPolicySet: true, ShieldBlockAdult: true}
	preset, set, _, _, adult := resolveShieldForCreate(entitlement.ShieldPresetAggressive, nil, replaced)
	if preset != entitlement.ShieldPresetAggressive || set || adult {
		t.Fatalf("explicit legacy preset replaces toggles, got %s set=%v adult=%v", preset, set, adult)
	}
}
