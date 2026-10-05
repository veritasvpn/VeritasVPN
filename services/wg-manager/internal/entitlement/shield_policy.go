package entitlement

import (
	"bytes"
	"encoding/json"
	"fmt"
)

// ShieldFlags are the three Premium Veritas Shield toggles.
// Clients send this object. The server does not accept a raw category list.
//
// Absent flags keep the peer's stored preset (today's Standard for existing
// rows). When a Premium user sets flags, the default screen state is
// malicious on, ads off, adult off — the same threat coverage as Standard.
type ShieldFlags struct {
	BlockMalicious bool `json:"block_malicious"`
	BlockAds       bool `json:"block_ads"`
	BlockAdult     bool `json:"block_adult"`
}

// DefaultShieldFlags is the Premium screen default.
func DefaultShieldFlags() ShieldFlags {
	return ShieldFlags{BlockMalicious: true, BlockAds: false, BlockAdult: false}
}

// FlagsForPreset is the toggle view of a legacy preset.
// Security and Standard both show malicious on / ads off / adult off.
// Trackers are not a toggle; Security's missing trackers stay on the preset
// until the user saves explicit flags.
func FlagsForPreset(preset string) ShieldFlags {
	if NormalizeShieldPreset(preset) == ShieldPresetAggressive {
		return ShieldFlags{BlockMalicious: true, BlockAds: true, BlockAdult: false}
	}
	return DefaultShieldFlags()
}

// EffectiveFlags returns explicit toggles when they have been saved, otherwise
// the preset view. Callers must not treat the raw boolean columns as the
// policy while policySet is false (those columns default to false).
func EffectiveFlags(policySet, malicious, ads, adult bool, preset string) ShieldFlags {
	if policySet {
		return ShieldFlags{BlockMalicious: malicious, BlockAds: ads, BlockAdult: adult}
	}
	return FlagsForPreset(preset)
}

// AliasPreset is the closest legacy preset for agents that only understand
// SHIELD_PRESET. Adult and "malicious off" are not representable there, so
// those agents keep threat blocking. New agents use the explicit flags.
func AliasPreset(flags ShieldFlags) string {
	if flags.BlockAds {
		return ShieldPresetAggressive
	}
	return ShieldPresetStandard
}

// CheckShieldUpdate rejects toggle changes from accounts that are not Premium.
// Every value of the three toggles is a Premium control, including turning
// the default malicious filter off.
func CheckShieldUpdate(tier string) error {
	if NormalizeTier(tier) != TierPremium {
		return &PlanError{
			Code:    "subscription_required",
			Message: "Veritas Shield filters require an active Premium subscription",
		}
	}
	return nil
}

// CheckShieldPreset gates the legacy preset write. Aggressive is the only
// preset that turns ads on, so it requires Premium. Security and Standard
// stay available as the compatibility alias for peers that never saved flags.
func CheckShieldPreset(tier, preset string) error {
	if NormalizeShieldPreset(preset) != ShieldPresetAggressive {
		return nil
	}
	return CheckShieldUpdate(tier)
}

// ParseShieldFlags decodes the shield object. Unknown fields (including a
// raw category list) are rejected so clients cannot name categories themselves.
func ParseShieldFlags(body []byte) (ShieldFlags, error) {
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.DisallowUnknownFields()
	var raw struct {
		BlockMalicious *bool `json:"block_malicious"`
		BlockAds       *bool `json:"block_ads"`
		BlockAdult     *bool `json:"block_adult"`
	}
	if err := dec.Decode(&raw); err != nil {
		return ShieldFlags{}, fmt.Errorf("shield only accepts block_malicious, block_ads, and block_adult")
	}
	if raw.BlockMalicious == nil || raw.BlockAds == nil || raw.BlockAdult == nil {
		return ShieldFlags{}, fmt.Errorf("shield.block_malicious, shield.block_ads, and shield.block_adult are required")
	}
	return ShieldFlags{
		BlockMalicious: *raw.BlockMalicious,
		BlockAds:       *raw.BlockAds,
		BlockAdult:     *raw.BlockAdult,
	}, nil
}
