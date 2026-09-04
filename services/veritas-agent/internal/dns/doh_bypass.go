package dns

// Built-in DoH / encrypted-DNS service hostnames. Queried names that match
// these (or are subdomains via Blocked suffix matching) get NXDOMAIN so apps
// that look up resolver endpoints through the tunnel gateway cannot bootstrap
// their own DoH path. Complements nftables drops of known resolver anycast IPs.
var builtInDoHBypassDomains = []string{
	"cloudflare-dns.com",
	"one.one.one.one",
	"dns.google",
	"dns.google.com",
	"dns.quad9.net",
	"dns9.quad9.net",
	"dns10.quad9.net",
	"dns11.quad9.net",
	"dns.adguard.com",
	"dns.adguard-dns.com",
	"dns.nextdns.io",
	"doh.opendns.com",
	"doh.familyshield.opendns.com",
	"dns.mullvad.net",
	"doh.cleanbrowsing.org",
	"dns.controld.com",
	"freedns.controld.com",
	"dns.sb",
	"doh.dns.sb",
	"dns.alidns.com",
	"doh.pub",
	"dns.twnic.tw",
	"chrome.cloudflare-dns.com",
	"firefox.cloudflare-dns.com",
	"mozilla.cloudflare-dns.com",
	"security.cloudflare-dns.com",
	"family.cloudflare-dns.com",
	// Additional common encrypted-DNS fronts (NXDOMAIN via gateway).
	"dns64.dns.google",
	"ordns.he.net",
	"doh.li",
	"dns.switch.ch",
	"dns.osl.basefarm.net",
	"doh.libredns.gr",
	"dns.digitale-gesellschaft.ch",
	"dnsforge.de",
	"doh.ffmuc.net",
	"wikimedia-dns.org",
}
