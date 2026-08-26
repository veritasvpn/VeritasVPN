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

	// PremiumMaxPortForwards is the max concurrent active/pending port forwards.
	PremiumMaxPortForwards = 2
	// RecommendedExternalPortMin/Max are suggested to clients in API errors.
	RecommendedExternalPortMin = 40000
	RecommendedExternalPortMax = 49999
)

// reservedExternalPorts are denied for inbound port forwards (node services).
var reservedExternalPorts = map[int]struct{}{
	22: {}, 80: {}, 443: {}, 3000: {}, 3100: {}, 2019: {}, 4222: {},
	5432: {}, 6379: {}, 6443: {}, 8080: {}, 8443: {}, 9090: {}, 51820: {},
}

func init() {
	// 20000-20100 reserved for future node services.
	for p := 20000; p <= 20100; p++ {
		reservedExternalPorts[p] = struct{}{}
	}
}

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

// CheckCreatePortForward enforces Premium-only port forwarding and the per-account cap.
func CheckCreatePortForward(tier string, currentCount int) error {
	if NormalizeTier(tier) != TierPremium {
		return &PlanError{
			Code:    "subscription_required",
			Message: "port forwarding requires a Premium subscription",
		}
	}
	if currentCount >= PremiumMaxPortForwards {
		return &PlanError{
			Code: "plan_port_forward_limit",
			Message: fmt.Sprintf(
				"plan port-forward limit reached (%d). Remove an existing forward or upgrade",
				PremiumMaxPortForwards,
			),
		}
	}
	return nil
}

// IsReservedExternalPort reports whether an external port is denied for forwarding.
func IsReservedExternalPort(port int) bool {
	_, ok := reservedExternalPorts[port]
	return ok
}

// ValidateExternalPort checks range and reserved ports. On failure the message
// recommends the 40000-49999 range.
func ValidateExternalPort(port int) error {
	if port < 1024 || port > 65535 {
		return &PlanError{
			Code: "invalid_external_port",
			Message: fmt.Sprintf(
				"external_port must be between 1024 and 65535 (recommended %d-%d)",
				RecommendedExternalPortMin, RecommendedExternalPortMax,
			),
		}
	}
	if IsReservedExternalPort(port) {
		return &PlanError{
			Code: "reserved_external_port",
			Message: fmt.Sprintf(
				"external_port %d is reserved for node services; use %d-%d",
				port, RecommendedExternalPortMin, RecommendedExternalPortMax,
			),
		}
	}
	return nil
}

// ValidateInternalPort checks the destination port range.
func ValidateInternalPort(port int) error {
	if port < 1 || port > 65535 {
		return &PlanError{
			Code:    "invalid_internal_port",
			Message: "internal_port must be between 1 and 65535",
		}
	}
	return nil
}

// NormalizeProtocol lowercases and validates tcp/udp.
func NormalizeProtocol(protocol string) (string, error) {
	p := strings.ToLower(strings.TrimSpace(protocol))
	switch p {
	case "tcp", "udp":
		return p, nil
	default:
		return "", &PlanError{
			Code:    "invalid_protocol",
			Message: "protocol must be tcp or udp",
		}
	}
}
