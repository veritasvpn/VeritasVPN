package dns

import (
	"os"
	"strings"
)

// Shield category identifiers (Veritas Shield Phase 1).
const (
	CategoryMalware  = "malware"
	CategoryPhishing = "phishing"
	CategoryScam     = "scam"
	CategoryCrypto   = "crypto"
	CategoryTrackers = "trackers"
	CategoryAds      = "ads"
)

// DefaultShieldCategories excludes ads (highest false-positive risk).
var DefaultShieldCategories = []string{
	CategoryMalware,
	CategoryPhishing,
	CategoryScam,
	CategoryCrypto,
	CategoryTrackers,
}

// defaultCategoryURLs are used when a category is enabled but has no
// DNS_BLOCKLIST_URLS_<CATEGORY> override.
var defaultCategoryURLs = map[string]string{
	CategoryMalware:  "https://urlhaus.abuse.ch/downloads/hostfile/",
	CategoryPhishing: "https://phishing.army/download/phishing_army_blocklist_extended.txt",
	// CERT Polska domain blocklist — phishing/malware/scam mix used for scam coverage.
	CategoryScam: "https://hole.cert.pl/domains/domains.txt",
	CategoryCrypto: "https://raw.githubusercontent.com/hoshsadiq/adblock-nocoin-list/master/hosts.txt",
	// OISD small — ads+trackers compact list; used for trackers (and ads when enabled).
	CategoryTrackers: "https://small.oisd.nl/",
	CategoryAds: "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
}

var knownCategories = map[string]struct{}{
	CategoryMalware:  {},
	CategoryPhishing: {},
	CategoryScam:     {},
	CategoryCrypto:   {},
	CategoryTrackers: {},
	CategoryAds:      {},
}

// ShieldConfig configures categorized Veritas Shield blocklists.
type ShieldConfig struct {
	// Categories is the enabled set in priority order (first match wins).
	Categories []string
	// URLs maps category → HTTPS feed URLs.
	URLs map[string][]string
	// LegacyURLs is DNS_BLOCKLIST_URLS (malware+phishing fallback).
	LegacyURLs string
	StateFile  string
}

// LoadShieldSourcesFromEnv builds category → URL lists from environment.
// DNS_SHIELD_CATEGORIES defaults to AllFeedCategories so every preset's feeds
// are loaded; per-peer presets choose which categories apply at query time.
// Per-category: DNS_BLOCKLIST_URLS_MALWARE, _PHISHING, _SCAM, _CRYPTO, _TRACKERS, _ADS.
// Legacy DNS_BLOCKLIST_URLS applies to malware+phishing when those category
// vars are unset.
func LoadShieldSourcesFromEnv() (categories []string, urls map[string][]string) {
	rawCats := strings.TrimSpace(os.Getenv("DNS_SHIELD_CATEGORIES"))
	if rawCats == "" {
		categories = append([]string(nil), AllFeedCategories...)
	} else {
		for _, part := range strings.FieldsFunc(rawCats, func(r rune) bool {
			return r == ',' || r == ';' || r == ' ' || r == '\n' || r == '\t'
		}) {
			c := strings.ToLower(strings.TrimSpace(part))
			if _, ok := knownCategories[c]; ok {
				categories = append(categories, c)
			}
		}
		if len(categories) == 0 {
			categories = append([]string(nil), AllFeedCategories...)
		}
	}

	urls = make(map[string][]string, len(categories))
	legacy := splitSources(os.Getenv("DNS_BLOCKLIST_URLS"))

	for _, cat := range categories {
		envKey := "DNS_BLOCKLIST_URLS_" + strings.ToUpper(cat)
		specific := splitSources(os.Getenv(envKey))
		if len(specific) > 0 {
			urls[cat] = specific
			continue
		}
		// Legacy single list → malware + phishing only.
		if len(legacy) > 0 && (cat == CategoryMalware || cat == CategoryPhishing) {
			urls[cat] = append([]string(nil), legacy...)
			continue
		}
		if def, ok := defaultCategoryURLs[cat]; ok && def != "" {
			urls[cat] = []string{def}
		}
	}
	return categories, urls
}
