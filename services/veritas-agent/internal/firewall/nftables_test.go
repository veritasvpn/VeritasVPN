package firewall

import (
	"strings"
	"testing"
)

func TestBuildRulesetIsFailClosedAndConvergent(t *testing.T) {
	rules := buildRuleset("veritas", "wg0", "enp1s0", "10.42.0.0/24", "10.43.0.0/16", 51820, 50)
	for _, want := range []string{
		"destroy table inet veritas",
		"policy drop",
		"meter vpn_upload",
		"meter vpn_download",
		"udp dport 51820 accept",
	} {
		if !strings.Contains(rules, want) {
			t.Fatalf("ruleset missing %q:\n%s", want, rules)
		}
	}
	if got := strings.Count(rules, "meter vpn_upload"); got != 1 {
		t.Fatalf("upload meter appears %d times, want 1", got)
	}
	if got := strings.Count(rules, "meter vpn_download"); got != 1 {
		t.Fatalf("download meter appears %d times, want 1", got)
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
