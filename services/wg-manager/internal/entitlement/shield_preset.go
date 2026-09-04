package entitlement

import "strings"

// Shield presets for Veritas Shield Phase 2 (mirrored in veritas-agent).
const (
	ShieldPresetSecurity   = "security"
	ShieldPresetStandard   = "standard"
	ShieldPresetAggressive = "aggressive"
)

// NormalizeShieldPreset returns a known preset or standard.
func NormalizeShieldPreset(raw string) string {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case ShieldPresetSecurity, ShieldPresetStandard, ShieldPresetAggressive:
		return strings.ToLower(strings.TrimSpace(raw))
	default:
		return ShieldPresetStandard
	}
}
