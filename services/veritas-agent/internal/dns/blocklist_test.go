package dns

import (
	"encoding/json"
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

func TestAdultFeedIsLoadedAndOffByDefault(t *testing.T) {
	t.Setenv("DNS_SHIELD_CATEGORIES", "")
	t.Setenv("DNS_BLOCKLIST_URLS", "")
	t.Setenv("DNS_BLOCKLIST_URLS_ADULT", "")
	cats, urls := LoadShieldSourcesFromEnv()
	found := false
	for _, c := range cats {
		if c == CategoryAdult {
			found = true
		}
	}
	if !found {
		t.Fatal("feed load set should include adult so the toggle can apply without a reload")
	}
	if len(urls[CategoryAdult]) == 0 {
		t.Fatal("expected default adult feeds")
	}
	for _, raw := range urls[CategoryAdult] {
		if !strings.HasPrefix(raw, "https://") {
			t.Fatalf("adult feed must be https, got %q", raw)
		}
	}
	if CategoryEnabled(DefaultPreset, CategoryAdult) || CategoryEnabled(PresetAggressive, CategoryAdult) {
		t.Fatal("presets must not enable adult; it is an explicit toggle")
	}
	b := NewShieldBlocklist(AllFeedCategories, nil, "", 0, nil, nil)
	if cat, ok := b.BlockedCategory(AdultProtectionTestDomain); !ok || cat != CategoryAdult {
		t.Fatalf("adult test domain category=%q ok=%v", cat, ok)
	}
}

func TestShieldPolicyJSON(t *testing.T) {
	var policy ShieldPolicy
	if err := json.Unmarshal([]byte(`{"explicit":true,"block_malicious":true,"block_ads":false,"block_adult":true}`), &policy); err != nil {
		t.Fatal(err)
	}
	if !policy.Explicit || !policy.BlockMalicious || policy.BlockAds || !policy.BlockAdult || !policy.BlockTrackers {
		t.Fatalf("legacy wire flags must keep trackers on: %+v", policy)
	}
	if !containsCategory(CategoriesForFlags(policy.Flags()), CategoryAdult) || !containsCategory(CategoriesForFlags(policy.Flags()), CategoryTrackers) {
		t.Fatal("adult flag must map to adult, and omitted trackers stay on")
	}
	var off ShieldPolicy
	if err := json.Unmarshal([]byte(`{"explicit":true,"block_malicious":true,"block_ads":true,"block_adult":false,"block_trackers":false}`), &off); err != nil {
		t.Fatal(err)
	}
	cats := CategoriesForFlags(off.Flags())
	if off.BlockTrackers || !containsCategory(cats, CategoryAds) || containsCategory(cats, CategoryTrackers) {
		t.Fatalf("trackers off must not merge into ads: %+v cats=%v", off, cats)
	}
}

func TestCategoriesForFlags(t *testing.T) {
	def := CategoriesForFlags(DefaultShieldFlags())
	for _, want := range []string{CategoryMalware, CategoryPhishing, CategoryScam, CategoryCrypto, CategoryTrackers} {
		if !containsCategory(def, want) {
			t.Fatalf("default flags missing %s: %v", want, def)
		}
	}
	if containsCategory(def, CategoryAds) || containsCategory(def, CategoryAdult) {
		t.Fatalf("default flags should leave ads and adult off: %v", def)
	}

	ads := CategoriesForFlags(ShieldFlags{BlockMalicious: true, BlockAds: true})
	if !containsCategory(ads, CategoryAds) {
		t.Fatal("block ads should enable the ads category")
	}

	adult := CategoriesForFlags(ShieldFlags{BlockMalicious: false, BlockAdult: true, BlockTrackers: true})
	if containsCategory(adult, CategoryMalware) || containsCategory(adult, CategoryPhishing) {
		t.Fatal("malicious off should drop threat categories")
	}
	if !containsCategory(adult, CategoryTrackers) || !containsCategory(adult, CategoryAdult) || containsCategory(adult, CategoryAds) {
		t.Fatalf("trackers follow their toggle and adult stays independent: %v", adult)
	}
	trackersOff := CategoriesForFlags(ShieldFlags{BlockMalicious: true, BlockAds: true, BlockTrackers: false})
	if containsCategory(trackersOff, CategoryTrackers) || !containsCategory(trackersOff, CategoryAds) {
		t.Fatalf("ads must not imply trackers: %v", trackersOff)
	}
}

func TestForwarderAppliesFlagsPerPeerWithoutReconnect(t *testing.T) {
	bl := NewShieldBlocklist(AllFeedCategories, nil, "", 0, nil, nil)
	bl.replace(map[string]string{
		ProtectedDNSTestDomain:    CategoryMalware,
		AdultProtectionTestDomain: CategoryAdult,
		"ads.example":             CategoryAds,
		"track.example":           CategoryTrackers,
	}, nil, time.Unix(0, 0))
	f := &Forwarder{
		blocklist:     bl,
		policyByIP:    map[string]peerPolicy{},
		defaultPreset: DefaultPreset,
		allowlist:     map[string]struct{}{},
	}

	if cat, ok := f.shouldBlock("10.0.0.8", ProtectedDNSTestDomain); !ok || cat != CategoryMalware {
		t.Fatalf("default standard should block malware test, cat=%q ok=%v", cat, ok)
	}
	if _, ok := f.shouldBlock("10.0.0.8", AdultProtectionTestDomain); ok {
		t.Fatal("adult test domain must resolve until the adult toggle is on")
	}
	if _, ok := f.shouldBlock("10.0.0.8", "ads.example"); ok {
		t.Fatal("ads must stay off on the default policy")
	}
	if _, ok := f.shouldBlock("10.0.0.8", "track.example"); !ok {
		t.Fatal("peers with no saved flags keep Standard, which blocks trackers")
	}

	f.ApplyPeerShield([]string{"10.0.0.8/32"}, PresetStandard, &ShieldPolicy{
		Explicit: true, BlockMalicious: true, BlockAds: true, BlockAdult: true, BlockTrackers: true,
	})
	for _, name := range []string{ProtectedDNSTestDomain, AdultProtectionTestDomain, "ads.example", "track.example"} {
		if _, ok := f.shouldBlock("10.0.0.8", name); !ok {
			t.Fatalf("expected %s blocked after explicit flags", name)
		}
	}
	if _, ok := f.shouldBlock("10.0.0.9", AdultProtectionTestDomain); ok {
		t.Fatal("another tunnel IP must keep the default policy")
	}

	f.ApplyPeerShield([]string{"10.0.0.8/32"}, PresetAggressive, &ShieldPolicy{
		Explicit: true, BlockMalicious: true, BlockAds: true, BlockAdult: false, BlockTrackers: false,
	})
	if _, ok := f.shouldBlock("10.0.0.8", "track.example"); ok {
		t.Fatal("block trackers off must stop blocking tracker domains without a tunnel rebuild")
	}
	if _, ok := f.shouldBlock("10.0.0.8", "ads.example"); !ok {
		t.Fatal("turning trackers off must leave the ads category in place")
	}

	f.ApplyPeerShield([]string{"10.0.0.8/32"}, PresetAggressive, &ShieldPolicy{
		Explicit: true, BlockMalicious: false, BlockAds: false, BlockAdult: false, BlockTrackers: false,
	})
	if _, ok := f.shouldBlock("10.0.0.8", ProtectedDNSTestDomain); ok {
		t.Fatal("malicious off must stop blocking the malware test domain")
	}
	if _, ok := f.shouldBlock("10.0.0.8", "track.example"); ok {
		t.Fatal("trackers follow their own toggle when it is off")
	}

	f.SetPeerPreset([]string{"10.7.0.2/32"}, PresetSecurity)
	if _, ok := f.shouldBlock("10.7.0.2", "track.example"); ok {
		t.Fatal("legacy security preset must not enable trackers")
	}
	if _, ok := f.shouldBlock("10.7.0.2", ProtectedDNSTestDomain); !ok {
		t.Fatal("legacy security preset still blocks malware")
	}

	f.allowlist = parseAllowlist("ads.example")
	f.ApplyPeerShield([]string{"10.0.0.8/32"}, "", &ShieldPolicy{Explicit: true, BlockAds: true, BlockMalicious: true, BlockTrackers: true})
	if _, ok := f.shouldBlock("10.0.0.8", "ads.example"); ok {
		t.Fatal("allowlist must win over an enabled ads category")
	}
}

func containsCategory(categories []string, want string) bool {
	for _, category := range categories {
		if category == want {
			return true
		}
	}
	return false
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
