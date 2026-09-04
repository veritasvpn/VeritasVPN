package firewall

import (
	"net"
	"os"
	"strings"
)

// Default public DNS-over-HTTPS resolver anycast addresses (IPv4).
// Scoped drops on wg→egress TCP/UDP 443 only; the agent’s own DoH uses host
// OUTPUT and is unaffected. Keep this list to dedicated resolver IPs — not
// whole CDN ASNs — to avoid breaking ordinary HTTPS.
var defaultDoHBlockIPv4 = []string{
	// Cloudflare
	"1.1.1.1", "1.0.0.1",
	"1.1.1.2", "1.0.0.2",
	"1.1.1.3", "1.0.0.3",
	// Google Public DNS
	"8.8.8.8", "8.8.4.4",
	// Quad9
	"9.9.9.9", "149.112.112.112",
	"9.9.9.10", "149.112.112.10",
	"9.9.9.11", "149.112.112.11",
	// OpenDNS
	"208.67.222.222", "208.67.220.220",
	"208.67.222.123", "208.67.220.123",
	// AdGuard DNS
	"94.140.14.14", "94.140.15.15",
	"94.140.14.15", "94.140.15.16",
	// CleanBrowsing
	"185.228.168.9", "185.228.169.9",
	"185.228.168.10", "185.228.169.11",
	// Mullvad DNS
	"194.242.2.2", "194.242.2.3",
	"194.242.2.4", "194.242.2.5",
	"194.242.2.6", "194.242.2.9",
	// Control D
	"76.76.2.0", "76.76.10.0",
	"76.76.2.1", "76.76.10.1",
	// NextDNS anycast (common resolver fronts)
	"45.90.28.0", "45.90.30.0",
}

// Known public DoH anycast IPv6 addresses (same providers as IPv4 list).
var defaultDoHBlockIPv6 = []string{
	// Cloudflare
	"2606:4700:4700::1111", "2606:4700:4700::1001",
	"2606:4700:4700::1112", "2606:4700:4700::1002",
	"2606:4700:4700::1113", "2606:4700:4700::1003",
	// Google
	"2001:4860:4860::8888", "2001:4860:4860::8844",
	// Quad9
	"2620:fe::fe", "2620:fe::9",
	"2620:fe::10", "2620:fe::fe:10",
	"2620:fe::11", "2620:fe::fe:11",
	// OpenDNS
	"2620:119:35::35", "2620:119:53::53",
	// AdGuard
	"2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",
	"2a10:50c0::1:ff", "2a10:50c0::2:ff",
	// Mullvad
	"2a07:e340::2", "2a07:e340::3",
	"2a07:e340::4", "2a07:e340::5",
	"2a07:e340::6", "2a07:e340::9",
	// Control D
	"2606:1a40::", "2606:1a40:1::",
	"2606:1a40::1", "2606:1a40:1::1",
	// NextDNS
	"2a07:a8c0::", "2a07:a8c1::",
}

// doHBlockLists returns IPv4 and IPv6 addresses dropped for app DoH from VPN clients.
// Set DOH_BLOCK_IPS=none to disable. Optional DOH_BLOCK_EXTRA_IPS merges IPv4/IPv6 extras.
func doHBlockLists() (v4, v6 []string) {
	if strings.EqualFold(strings.TrimSpace(os.Getenv("DOH_BLOCK_IPS")), "none") {
		return nil, nil
	}
	seen4 := make(map[string]struct{}, len(defaultDoHBlockIPv4)+8)
	seen6 := make(map[string]struct{}, len(defaultDoHBlockIPv6)+8)
	add := func(raw string) {
		ip := net.ParseIP(strings.TrimSpace(raw))
		if ip == nil {
			return
		}
		if ip4 := ip.To4(); ip4 != nil {
			s := ip4.String()
			if _, ok := seen4[s]; ok {
				return
			}
			seen4[s] = struct{}{}
			v4 = append(v4, s)
			return
		}
		s := ip.String()
		if _, ok := seen6[s]; ok {
			return
		}
		seen6[s] = struct{}{}
		v6 = append(v6, s)
	}
	for _, ip := range defaultDoHBlockIPv4 {
		add(ip)
	}
	for _, ip := range defaultDoHBlockIPv6 {
		add(ip)
	}
	for _, part := range strings.FieldsFunc(os.Getenv("DOH_BLOCK_EXTRA_IPS"), func(r rune) bool {
		return r == ',' || r == ';' || r == ' ' || r == '\n' || r == '\t'
	}) {
		add(part)
	}
	return v4, v6
}

// Deprecated alias kept for older call sites/tests; prefer doHBlockLists.
func doHBlockIPs() []string {
	v4, _ := doHBlockLists()
	return v4
}
