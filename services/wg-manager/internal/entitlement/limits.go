package entitlement

import (
	"fmt"
	"strings"
)

const (
	TierFree    = "free"
	TierPremium = "premium"

	// FreeMaxDevices is the max concurrent WireGuard peers for Free accounts.
	FreeMaxDevices = 1
	// PremiumMaxDevices is the max concurrent WireGuard peers for Premium.
	PremiumMaxDevices = 5
)

// PlanError is returned when CreatePeer violates plan limits.
type PlanError struct {
	Code    string
	Message string
}

func (e *PlanError) Error() string {
	return e.Message
}

func SubscriptionRequired() *PlanError {
	return &PlanError{Code: "subscription_required", Message: "an active paid subscription is required"}
}

func DeviceLimit(code string, max int) *PlanError {
	return &PlanError{
		Code:    code,
		Message: fmt.Sprintf("plan device limit reached (%d). Upgrade to Premium for more devices", max),
	}
}

func RegionDenied(region string) *PlanError {
	return &PlanError{
		Code:    "plan_region_denied",
		Message: fmt.Sprintf("region %q is not available on the Free plan", region),
	}
}

// NormalizeTier maps empty/unknown tiers to free; premium stays premium.
func NormalizeTier(tier string) string {
	switch strings.ToLower(strings.TrimSpace(tier)) {
	case TierPremium:
		return TierPremium
	default:
		return TierFree
	}
}

// MaxDevices returns the peer cap for a tier.
func MaxDevices(tier string) int {
	if NormalizeTier(tier) == TierPremium {
		return PremiumMaxDevices
	}
	return FreeMaxDevices
}

// ParseFreeRegions parses FREE_ALLOWED_REGIONS (comma-separated).
// Empty means Free may use any online region (appropriate while the fleet is small).
func ParseFreeRegions(raw string) []string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil
	}
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

// CheckCreatePeer validates device count and optional Free region allow-list
// before a peer is allocated. preferredRegion may be empty (scheduler picks).
// selectedRegionHint is unused here; region is checked against preferredRegion
// and/or freeAllowed when set.
func CheckCreatePeer(tier string, currentPeerCount int, preferredRegion string, freeAllowed []string) error {
	tier = NormalizeTier(tier)
	if tier != TierPremium {
		return SubscriptionRequired()
	}
	max := MaxDevices(tier)
	if currentPeerCount >= max {
		code := "plan_device_limit"
		if tier == TierFree {
			code = "plan_device_limit_free"
		}
		return DeviceLimit(code, max)
	}

	if tier == TierFree && len(freeAllowed) > 0 {
		if preferredRegion != "" && !regionAllowed(preferredRegion, freeAllowed) {
			return RegionDenied(preferredRegion)
		}
	}
	return nil
}

// CheckSelectedRegion enforces Free allow-list after the scheduler picks a server.
func CheckSelectedRegion(tier, selectedRegion string, freeAllowed []string) error {
	if NormalizeTier(tier) != TierFree || len(freeAllowed) == 0 {
		return nil
	}
	if !regionAllowed(selectedRegion, freeAllowed) {
		return RegionDenied(selectedRegion)
	}
	return nil
}

func regionAllowed(region string, allowed []string) bool {
	for _, a := range allowed {
		if strings.EqualFold(a, region) {
			return true
		}
	}
	return false
}
