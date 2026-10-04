package firewall

import (
	"strings"
	"testing"
)

func TestVPNIsolationPrecedesExceptions(t *testing.T) {
	rules := buildRuleset("veritas", "wg0", "enp1s0", "10.42.0.0/24", "10.43.0.0/16", "10.0.0.0/24", "10.0.0.1", 51820, nil, nil)
	for _, iface := range []string{"cni0", "flannel.1"} {
		if strings.Contains(rules, `forward oifname "`+iface+`" accept`) {
			t.Fatal("unrestricted CNI exception")
		}
		deny := strings.Index(rules, `forward iifname "wg0" oifname "`+iface+`" counter drop`)
		allow := strings.Index(rules, `forward iifname != "wg0" oifname "`+iface+`" accept`)
		if deny < 0 || allow < deny {
			t.Fatal("CNI isolation missing or out of order")
		}
	}
	if strings.Index(rules, `ip daddr 10.0.0.0/8 counter drop`) > strings.Index(rules, `forward ct state established,related accept`) {
		t.Fatal("established traffic bypasses isolation")
	}
}
