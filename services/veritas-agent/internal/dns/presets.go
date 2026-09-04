package dns

import (
	"os"
	"strings"
)

// Shield presets (Phase 2). Each maps to an enabled category set.
const (
	PresetSecurity   = "security"
	PresetStandard   = "standard"
	PresetAggressive = "aggressive"
)

// DefaultPreset is used when a tunnel IP has no peer mapping yet.
const DefaultPreset = PresetStandard

// AllFeedCategories is the full feed load set (includes ads for Aggressive).
var AllFeedCategories = []string{
	CategoryMalware,
	CategoryPhishing,
	CategoryScam,
	CategoryCrypto,
	CategoryTrackers,
	CategoryAds,
}

var presetCategories = map[string][]string{
	// Security: threat-focused, no trackers/ads (fewer FPs on CDNs).
	PresetSecurity: {
		CategoryMalware,
		CategoryPhishing,
		CategoryScam,
		CategoryCrypto,
	},
	// Standard: Phase 1 default (ads off).
	PresetStandard: {
		CategoryMalware,
		CategoryPhishing,
		CategoryScam,
		CategoryCrypto,
		CategoryTrackers,
	},
	// Aggressive: Standard + ads.
	PresetAggressive: {
		CategoryMalware,
		CategoryPhishing,
		CategoryScam,
		CategoryCrypto,
		CategoryTrackers,
		CategoryAds,
	},
}

// NormalizePreset returns a known preset or DefaultPreset.
func NormalizePreset(raw string) string {
	p := strings.ToLower(strings.TrimSpace(raw))
	if _, ok := presetCategories[p]; ok {
		return p
	}
	return DefaultPreset
}

// CategoriesForPreset returns the enabled category list for a preset.
func CategoriesForPreset(preset string) []string {
	cats := presetCategories[NormalizePreset(preset)]
	out := make([]string, len(cats))
	copy(out, cats)
	return out
}

// CategoryEnabled reports whether category is active under preset.
func CategoryEnabled(preset, category string) bool {
	category = strings.ToLower(strings.TrimSpace(category))
	for _, c := range CategoriesForPreset(preset) {
		if c == category {
			return true
		}
	}
	return false
}

// LoadAllowlistFromEnv parses DNS_SHIELD_ALLOWLIST (comma/space separated domains).
// Matching names (and subdomains) are never blocked — escape hatch for Aggressive FPs.
func LoadAllowlistFromEnv() map[string]struct{} {
	return parseAllowlist(os.Getenv("DNS_SHIELD_ALLOWLIST"))
}

func parseAllowlist(raw string) map[string]struct{} {
	out := make(map[string]struct{})
	for _, part := range strings.FieldsFunc(raw, func(r rune) bool {
		return r == ',' || r == ';' || r == ' ' || r == '\n' || r == '\t'
	}) {
		d := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(part)), ".")
		if d == "" || strings.Contains(d, "/") {
			continue
		}
		out[d] = struct{}{}
	}
	return out
}

// AllowlistMatch reports whether name or a parent domain is allowlisted.
func AllowlistMatch(allow map[string]struct{}, name string) bool {
	if len(allow) == 0 {
		return false
	}
	name = strings.TrimSuffix(strings.ToLower(name), ".")
	for {
		if _, ok := allow[name]; ok {
			return true
		}
		i := strings.IndexByte(name, '.')
		if i < 0 {
			return false
		}
		name = name[i+1:]
	}
}
