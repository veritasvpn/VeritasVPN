package netpolicy

import (
	"net"
	"testing"
)

func TestPublicBoundary(t *testing.T) {
	for _, raw := range []string{"", "0.0.0.1", "10.42.0.1", "100.100.36.115", "127.0.0.1", "169.254.169.254", "172.16.0.1", "192.168.0.6", "192.0.0.9", "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255", "::1", "::ffff:100.100.36.115", "64:ff9b::a00:1", "fc00::1", "fe80::1", "2001::1", "2001:db8::1", "2002:a00:1::1", "3fff::1", "ff02::1"} {
		if IsPublic(net.ParseIP(raw)) {
			t.Errorf("accepted reserved address %q", raw)
		}
	}
	for _, raw := range []string{"1.1.1.1", "8.8.8.8", "::ffff:8.8.8.8", "2606:4700:4700::1111", "2001:4860:4860::8888"} {
		if !IsPublic(net.ParseIP(raw)) {
			t.Errorf("rejected public address %q", raw)
		}
	}
	if AllPublic([]net.IP{net.ParseIP("1.1.1.1"), net.ParseIP("10.0.0.1")}) || AllPublic(nil) {
		t.Fatal("mixed/empty answers accepted")
	}
}
