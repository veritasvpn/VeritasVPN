package dns

import "strings"

// ShieldFlags are the three Premium toggles. Clients send these booleans.
// The agent maps them to categories; clients do not send category lists.
//
// Trackers are not a toggle in this release. Flag policy keeps them on so a
// peer that leaves Block malicious sites at its default still matches today's
// Standard preset (threats + trackers, ads off). Legacy presets still expand
// on their own when no explicit flags are set — Security stays tracker-free.
type ShieldFlags struct {
	BlockMalicious bool `json:"block_malicious"`
	BlockAds       bool `json:"block_ads"`
	BlockAdult     bool `json:"block_adult"`
}

// ShieldPolicy is the live per-peer payload from wg-manager.
// Explicit reports that the three toggles replace preset expansion.
type ShieldPolicy struct {
	Explicit       bool `json:"explicit"`
	BlockMalicious bool `json:"block_malicious"`
	BlockAds       bool `json:"block_ads"`
	BlockAdult     bool `json:"block_adult"`
}

// DefaultShieldFlags is the Premium screen default: malicious on, ads off, adult off.
func DefaultShieldFlags() ShieldFlags {
	return ShieldFlags{BlockMalicious: true, BlockAds: false, BlockAdult: false}
}

// Flags returns the toggle view of a policy payload.
func (p ShieldPolicy) Flags() ShieldFlags {
	return ShieldFlags{
		BlockMalicious: p.BlockMalicious,
		BlockAds:       p.BlockAds,
		BlockAdult:     p.BlockAdult,
	}
}

// CategoriesForFlags maps the three toggles onto the category set enforced
// for one tunnel IP. Trackers stay enabled. Malicious covers malware,
// phishing, scam, and crypto. Ads and adult follow their own toggles.
func CategoriesForFlags(flags ShieldFlags) []string {
	out := make([]string, 0, 7)
	if flags.BlockMalicious {
		out = append(out, CategoryMalware, CategoryPhishing, CategoryScam, CategoryCrypto)
	}
	out = append(out, CategoryTrackers)
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
