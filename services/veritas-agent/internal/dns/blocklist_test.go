package dns

import (
	"strings"
	"testing"
	"time"
)

func TestBlocklistParsesHostsAndAdblockFormats(t *testing.T) {
	domains := make(map[string]struct{})
	_, err := parseBlocklist(strings.NewReader("# comment\n0.0.0.0 malware.example\n127.0.0.1 phish.example # source\n||sub.bad.example^\ninvalid/path\n"), domains)
	if err != nil {
		t.Fatal(err)
	}
	for _, domain := range []string{"malware.example", "phish.example", "sub.bad.example"} {
		if _, ok := domains[domain]; !ok {
			t.Fatalf("expected %q to be parsed", domain)
		}
	}
}

func TestBlocklistMatchesSubdomains(t *testing.T) {
	b := NewBlocklist("", "", 0, nil, nil)
	b.replace(map[string]struct{}{"malware.example": {}}, time.Unix(0, 0))
	if !b.Blocked("cdn.malware.example") {
		t.Fatal("expected subdomain to be blocked")
	}
	if b.Blocked("safe.example") {
		t.Fatal("unexpected block")
	}
}

func TestBlocklistIncludesHarmlessProtectionTestDomain(t *testing.T) {
	b := NewBlocklist("", "", 0, nil, nil)
	if !b.Blocked(ProtectedDNSTestDomain) {
		t.Fatal("expected built-in DNS protection test domain to be blocked")
	}
}
