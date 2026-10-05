package entitlement

import (
	"bytes"
	"encoding/json"
	"fmt"
)

// DNS category identifiers. Clients never send these; the server maps toggles.
const (
	CategoryMalware  = "malware"
	CategoryPhishing = "phishing"
	CategoryScam     = "scam"
	CategoryCrypto   = "crypto"
	CategoryTrackers = "trackers"
	CategoryAds      = "ads"
	CategoryAdult    = "adult"
)

// ShieldFlags are the four Premium Veritas Shield toggles.
// Clients send this object. The server does not accept a raw category list.
//
// Absent flags keep the peer's stored preset (today's Standard for existing
// rows: malicious and trackers on, ads and adult off). When a Premium user
// sets flags, the default screen state is malicious on, trackers on, ads off,
// adult off — the same threat coverage as Standard.
type ShieldFlags struct {
	BlockMalicious bool `json:"block_malicious"`
	BlockAds       bool `json:"block_ads"`
	BlockAdult     bool `json:"block_adult"`
	BlockTrackers  bool `json:"block_trackers"`
}

// DefaultShieldFlags is the Premium screen default.
func DefaultShieldFlags() ShieldFlags {
	return ShieldFlags{BlockMalicious: true, BlockAds: false, BlockAdult: false, BlockTrackers: true}
}

// FlagsForPreset is the toggle view of a legacy preset.
// Security is threats only. Standard adds trackers. Aggressive adds ads.
// Adult is never part of a preset.
func FlagsForPreset(preset string) ShieldFlags {
	switch NormalizeShieldPreset(preset) {
	case ShieldPresetAggressive:
		return ShieldFlags{BlockMalicious: true, BlockAds: true, BlockAdult: false, BlockTrackers: true}
	case ShieldPresetSecurity:
		return ShieldFlags{BlockMalicious: true, BlockAds: false, BlockAdult: false, BlockTrackers: false}
	default:
		return DefaultShieldFlags()
	}
}

// EffectiveFlags returns explicit toggles when they have been saved, otherwise
// the preset view. Callers must not treat the raw boolean columns as the
// policy while policySet is false (those columns default to false).
func EffectiveFlags(policySet, malicious, ads, adult, trackers bool, preset string) ShieldFlags {
	if policySet {
		return ShieldFlags{
			BlockMalicious: malicious,
			BlockAds:       ads,
			BlockAdult:     adult,
			BlockTrackers:  trackers,
		}
	}
	return FlagsForPreset(preset)
}

// CategoriesForFlags maps the four toggles onto DNS categories.
// Block trackers enables only the trackers category. It is not folded into ads.
// Malicious covers malware, phishing, scam, and crypto.
func CategoriesForFlags(flags ShieldFlags) []string {
	out := make([]string, 0, 7)
	if flags.BlockMalicious {
		out = append(out, CategoryMalware, CategoryPhishing, CategoryScam, CategoryCrypto)
	}
	if flags.BlockTrackers {
		out = append(out, CategoryTrackers)
	}
	if flags.BlockAds {
		out = append(out, CategoryAds)
	}
	if flags.BlockAdult {
		out = append(out, CategoryAdult)
	}
	return out
}

// AliasPreset is the closest legacy preset for agents that only understand
// SHIELD_PRESET. Adult, "malicious off", and "trackers off" are not
// representable there, so those agents keep threat blocking (and trackers,
// unless the alias is security). New agents use the explicit flags.
func AliasPreset(flags ShieldFlags) string {
	if flags.BlockAds {
		return ShieldPresetAggressive
	}
	return ShieldPresetStandard
}

// CheckShieldUpdate rejects toggle changes from accounts that are not Premium.
// Every value of the four toggles is a Premium control, including turning
// the default malicious or tracker filter off.
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
// block_trackers defaults to true when an older client omits it, so a
// three-flag save does not drop tracker blocking.
func ParseShieldFlags(body []byte) (ShieldFlags, error) {
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.DisallowUnknownFields()
	var raw struct {
		BlockMalicious *bool `json:"block_malicious"`
		BlockAds       *bool `json:"block_ads"`
		BlockAdult     *bool `json:"block_adult"`
		BlockTrackers  *bool `json:"block_trackers"`
	}
	if err := dec.Decode(&raw); err != nil {
		return ShieldFlags{}, fmt.Errorf("shield only accepts block_malicious, block_ads, block_adult, and block_trackers")
	}
	if raw.BlockMalicious == nil || raw.BlockAds == nil || raw.BlockAdult == nil {
		return ShieldFlags{}, fmt.Errorf("shield.block_malicious, shield.block_ads, and shield.block_adult are required")
	}
	trackers := true
	if raw.BlockTrackers != nil {
		trackers = *raw.BlockTrackers
	}
	return ShieldFlags{
		BlockMalicious: *raw.BlockMalicious,
		BlockAds:       *raw.BlockAds,
		BlockAdult:     *raw.BlockAdult,
		BlockTrackers:  trackers,
	}, nil
}
