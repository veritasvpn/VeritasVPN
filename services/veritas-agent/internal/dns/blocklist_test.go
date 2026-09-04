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
	b := NewShieldBlocklist([]string{CategoryMalware}, nil, "", 0, nil, nil)
	b.replace(map[string]string{"malware.example": CategoryMalware}, map[string]int{CategoryMalware: 1}, time.Unix(0, 0))
	if !b.Blocked("cdn.malware.example") {
		t.Fatal("expected subdomain to be blocked")
	}
	if cat, ok := b.BlockedCategory("cdn.malware.example"); !ok || cat != CategoryMalware {
		t.Fatalf("expected malware category, got %q ok=%v", cat, ok)
	}
	if b.Blocked("safe.example") {
		t.Fatal("unexpected block")
	}
}

func TestBlocklistIncludesHarmlessProtectionTestDomain(t *testing.T) {
	b := NewShieldBlocklist(DefaultShieldCategories, nil, "", 0, nil, nil)
	if !b.Blocked(ProtectedDNSTestDomain) {
		t.Fatal("expected built-in DNS protection test domain to be blocked")
	}
}

func TestCategoryParseFirstWins(t *testing.T) {
	target := map[string]string{}
	_, err := parseBlocklistCategory(strings.NewReader("shared.example\n"), target, CategoryMalware)
	if err != nil {
		t.Fatal(err)
	}
	_, err = parseBlocklistCategory(strings.NewReader("shared.example\n"), target, CategoryAds)
	if err != nil {
		t.Fatal(err)
	}
	if target["shared.example"] != CategoryMalware {
		t.Fatalf("first category should win, got %q", target["shared.example"])
	}
}

func TestLoadShieldSourcesDefaultsExcludeAds(t *testing.T) {
	t.Setenv("DNS_SHIELD_CATEGORIES", "")
	t.Setenv("DNS_BLOCKLIST_URLS", "")
	for _, c := range []string{"MALWARE", "PHISHING", "SCAM", "CRYPTO", "TRACKERS", "ADS"} {
		t.Setenv("DNS_BLOCKLIST_URLS_"+c, "")
	}
	cats, urls := LoadShieldSourcesFromEnv()
	foundAds := false
	for _, c := range cats {
		if c == CategoryAds {
			foundAds = true
		}
	}
	if !foundAds {
		t.Fatal("feed load set should include ads so Aggressive preset can work")
	}
	if len(urls[CategoryMalware]) == 0 || len(urls[CategoryTrackers]) == 0 {
		t.Fatalf("expected default feeds, got %#v", urls)
	}
	// Default query preset still excludes ads.
	if CategoryEnabled(DefaultPreset, CategoryAds) {
		t.Fatal("standard preset must keep ads off")
	}
}

func TestPresetsAndAllowlist(t *testing.T) {
	if CategoryEnabled(PresetSecurity, CategoryTrackers) {
		t.Fatal("security preset should not enable trackers")
	}
	if !CategoryEnabled(PresetAggressive, CategoryAds) {
		t.Fatal("aggressive should enable ads")
	}
	allow := parseAllowlist("cdn.example, safe.test")
	if !AllowlistMatch(allow, "img.cdn.example") {
		t.Fatal("expected subdomain allowlist match")
	}
	if AllowlistMatch(allow, "evil.example") {
		t.Fatal("unexpected allowlist match")
	}
}
