package dns

import "testing"

func TestBuiltInDoHBypassDomainsAreBlocked(t *testing.T) {
	b := NewBlocklist("", "", 0, nil, nil)
	for _, name := range []string{
		"cloudflare-dns.com",
		"mozilla.cloudflare-dns.com",
		"dns.google",
		"dns.quad9.net",
		"dns.nextdns.io",
	} {
		if !b.Blocked(name) {
			t.Fatalf("expected %q to be blocked by built-in DoH deny list", name)
		}
	}
	if b.Blocked("example.com") {
		t.Fatalf("example.com must not be blocked by DoH deny list alone")
	}
}
