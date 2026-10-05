package service

import (
	"testing"

	"github.com/veritasvpn/services/wg-manager/internal/entitlement"
	"github.com/veritasvpn/services/wg-manager/internal/model"
)

func TestResolveShieldForCreateKeepsStoredPreset(t *testing.T) {
	replaced := &model.Peer{ShieldPreset: entitlement.ShieldPresetSecurity}
	preset, set, malicious, ads, adult, trackers := resolveShieldForCreate("", nil, replaced)
	if preset != entitlement.ShieldPresetSecurity || set || malicious || ads || adult || trackers {
		t.Fatalf("missing flags must keep the stored preset, got %s set=%v flags=%v %v %v %v", preset, set, malicious, ads, adult, trackers)
	}
}

func TestResolveShieldForCreateKeepsStoredTrackerFlag(t *testing.T) {
	replaced := &model.Peer{
		ShieldPreset:         entitlement.ShieldPresetStandard,
		ShieldPolicySet:      true,
		ShieldBlockMalicious: true,
		ShieldBlockTrackers:  false,
	}
	_, set, malicious, _, _, trackers := resolveShieldForCreate("", nil, replaced)
	if !set || !malicious || trackers {
		t.Fatalf("reconnect must keep an explicit trackers-off policy, set=%v malicious=%v trackers=%v", set, malicious, trackers)
	}
}

func TestResolveShieldForCreateExplicitFlags(t *testing.T) {
	flags := entitlement.ShieldFlags{BlockMalicious: true, BlockAds: false, BlockAdult: true, BlockTrackers: false}
	preset, set, malicious, ads, adult, trackers := resolveShieldForCreate(entitlement.ShieldPresetAggressive, &flags, &model.Peer{ShieldPreset: entitlement.ShieldPresetAggressive, ShieldPolicySet: true, ShieldBlockAds: true, ShieldBlockTrackers: true})
	if !set || !malicious || ads || !adult || trackers {
		t.Fatalf("explicit flags: set=%v %v %v %v %v", set, malicious, ads, adult, trackers)
	}
	if preset != entitlement.ShieldPresetStandard {
		t.Fatalf("adult without ads aliases to standard for old agents, got %s", preset)
	}
}

func TestResolveShieldForCreateLegacyPresetClearsFlags(t *testing.T) {
	replaced := &model.Peer{ShieldPreset: entitlement.ShieldPresetStandard, ShieldPolicySet: true, ShieldBlockAdult: true, ShieldBlockTrackers: true}
	preset, set, _, _, adult, trackers := resolveShieldForCreate(entitlement.ShieldPresetAggressive, nil, replaced)
	if preset != entitlement.ShieldPresetAggressive || set || adult || trackers {
		t.Fatalf("explicit legacy preset replaces toggles, got %s set=%v adult=%v trackers=%v", preset, set, adult, trackers)
	}
}
