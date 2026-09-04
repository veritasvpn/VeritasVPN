package firewall

import (
	"os"
	"testing"
)

func TestDoHBlockListsIncludeIPv6(t *testing.T) {
	t.Setenv("DOH_BLOCK_IPS", "")
	t.Setenv("DOH_BLOCK_EXTRA_IPS", "")
	v4, v6 := doHBlockLists()
	if len(v4) < 8 {
		t.Fatalf("expected default IPv4 DoH list, got %d", len(v4))
	}
	if len(v6) < 4 {
		t.Fatalf("expected default IPv6 DoH list, got %d", len(v6))
	}
	found := false
	for _, ip := range v6 {
		if ip == "2606:4700:4700::1111" {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("missing Cloudflare DoH IPv6 in list: %v", v6)
	}
}

func TestDoHBlockListsDisabled(t *testing.T) {
	t.Setenv("DOH_BLOCK_IPS", "none")
	v4, v6 := doHBlockLists()
	if v4 != nil || v6 != nil {
		t.Fatalf("expected nil lists when disabled, got %v %v", v4, v6)
	}
	_ = os.Unsetenv("DOH_BLOCK_IPS")
}
