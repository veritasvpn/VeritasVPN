package firewall

import (
	"net"
	"os"
	"strings"
)

// Default public DNS-over-HTTPS resolver anycast addresses.
// Scoped drops on wg→egress TCP/UDP 443 only; the agent’s own DoH uses host
// OUTPUT and is unaffected. Keep this list to dedicated resolver IPs — not
// whole CDN ASNs — to avoid breaking ordinary HTTPS.
var defaultDoHBlockIPs = []string{
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

// doHBlockIPs returns the IPv4 addresses dropped for app DoH from VPN clients.
// Set DOH_BLOCK_IPS=none to disable. Otherwise defaults are used and optional
// comma/space-separated extras from DOH_BLOCK_EXTRA_IPS are merged.
func doHBlockIPs() []string {
	if strings.EqualFold(strings.TrimSpace(os.Getenv("DOH_BLOCK_IPS")), "none") {
		return nil
	}
	seen := make(map[string]struct{}, len(defaultDoHBlockIPs)+8)
	out := make([]string, 0, len(defaultDoHBlockIPs)+8)
	add := func(raw string) {
		ip := net.ParseIP(strings.TrimSpace(raw))
		if ip == nil || ip.To4() == nil {
			return
		}
		s := ip.To4().String()
		if _, ok := seen[s]; ok {
			return
		}
		seen[s] = struct{}{}
		out = append(out, s)
	}
	for _, ip := range defaultDoHBlockIPs {
		add(ip)
	}
	for _, part := range strings.FieldsFunc(os.Getenv("DOH_BLOCK_EXTRA_IPS"), func(r rune) bool {
		return r == ',' || r == ';' || r == ' ' || r == '\n' || r == '\t'
	}) {
		add(part)
	}
	return out
}
