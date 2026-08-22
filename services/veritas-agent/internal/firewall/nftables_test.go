package firewall

import (
	"strings"
	"testing"
)

func TestBuildRulesetIsFailClosedAndConvergent(t *testing.T) {
	rules := buildRuleset("veritas", "wg0", "enp1s0", "10.42.0.0/24", "10.43.0.0/16", "10.0.0.0/24", "10.0.0.1", 51820)
	for _, want := range []string{
		"destroy table inet veritas",
		"policy drop",
		"udp dport 51820 accept",
		"iifname \"wg0\" oifname \"enp1s0\" accept",
		"ip daddr 192.168.0.0/16 counter drop",
		"ip daddr 10.0.0.0/8 counter drop",
		"iifname \"wg0\" oifname \"wg0\" counter drop",
		"ip daddr 10.0.0.1 udp dport 53 accept",
		"iifname != \"wg0\" ip saddr 10.42.0.0/24 accept",
		"masquerade",
		"type nat hook prerouting priority dstnat",
		"iifname \"wg0\" udp dport 53 redirect to :53",
	} {
		if !strings.Contains(rules, want) {
			t.Fatalf("ruleset missing %q:\n%s", want, rules)
		}
	}
	if strings.Contains(rules, "meter vpn_upload") {
		t.Fatalf("agent must not install bandwidth meters; host tc owns caps:\n%s", rules)
	}
	// Final VPN accept must not be a bare iifname wg0 accept.
	if strings.Contains(rules, "forward iifname \"wg0\" accept\n") {
		t.Fatalf("bare wg0 accept would allow LAN/peer lateral movement:\n%s", rules)
	}
}

func TestValidInterfaceName(t *testing.T) {
	for _, name := range []string{"wg0", "enp1s0", "tailscale0"} {
		if !validInterfaceName(name) {
			t.Errorf("expected %q to be valid", name)
		}
	}
	for _, name := range []string{"", "wg iface", "wg0; drop", "this-interface-name-is-too-long"} {
		if validInterfaceName(name) {
			t.Errorf("expected %q to be invalid", name)
		}
	}
}
