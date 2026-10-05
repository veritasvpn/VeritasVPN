package dns

import (
	"encoding/json"
	"strings"
)

// ShieldFlags are the four Premium toggles. Clients send these booleans.
// The agent maps them to categories; clients do not send category lists.
//
// Block trackers enables only the trackers category. It is not folded into ads.
// Legacy presets still expand on their own when no explicit flags are set —
// Security stays tracker-free, Standard keeps trackers on.
type ShieldFlags struct {
	BlockMalicious bool `json:"block_malicious"`
	BlockAds       bool `json:"block_ads"`
	BlockAdult     bool `json:"block_adult"`
	BlockTrackers  bool `json:"block_trackers"`
}

// ShieldPolicy is the live per-peer payload from wg-manager.
// Explicit reports that the toggles replace preset expansion.
type ShieldPolicy struct {
	Explicit       bool `json:"explicit"`
	BlockMalicious bool `json:"block_malicious"`
	BlockAds       bool `json:"block_ads"`
	BlockAdult     bool `json:"block_adult"`
	BlockTrackers  bool `json:"block_trackers"`
}

// DefaultShieldFlags is the Premium screen default: malicious on, trackers on, ads off, adult off.
func DefaultShieldFlags() ShieldFlags {
	return ShieldFlags{BlockMalicious: true, BlockAds: false, BlockAdult: false, BlockTrackers: true}
}

// Flags returns the toggle view of a policy payload.
func (p ShieldPolicy) Flags() ShieldFlags {
	return ShieldFlags{
		BlockMalicious: p.BlockMalicious,
		BlockAds:       p.BlockAds,
		BlockAdult:     p.BlockAdult,
		BlockTrackers:  p.BlockTrackers,
	}
}

// UnmarshalJSON defaults a missing block_trackers field to true. Older
// wg-manager builds omit it and always enforced trackers for explicit policy.
func (p *ShieldPolicy) UnmarshalJSON(data []byte) error {
	var raw struct {
		Explicit       bool  `json:"explicit"`
		BlockMalicious bool  `json:"block_malicious"`
		BlockAds       bool  `json:"block_ads"`
		BlockAdult     bool  `json:"block_adult"`
		BlockTrackers  *bool `json:"block_trackers"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	trackers := true
	if raw.BlockTrackers != nil {
		trackers = *raw.BlockTrackers
	}
	*p = ShieldPolicy{
		Explicit:       raw.Explicit,
		BlockMalicious: raw.BlockMalicious,
		BlockAds:       raw.BlockAds,
		BlockAdult:     raw.BlockAdult,
		BlockTrackers:  trackers,
	}
	return nil
}

// CategoriesForFlags maps the four toggles onto the category set enforced
// for one tunnel IP. Trackers follow their own toggle and are not merged
// into ads. Malicious covers malware, phishing, scam, and crypto.
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

func categorySet(categories []string) map[string]struct{} {
	out := make(map[string]struct{}, len(categories))
	for _, category := range categories {
		category = strings.ToLower(strings.TrimSpace(category))
		if category == "" {
			continue
		}
		out[category] = struct{}{}
	}
	return out
}
