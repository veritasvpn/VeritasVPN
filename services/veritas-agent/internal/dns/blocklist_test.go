package dns

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/veritasvpn/lib/logging"
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

// malwareFeedSamples are names present in the HaGeZi TIF mini feed on 2026-10-23.
// The unit tests serve them locally. TestLiveMalwareFeedContainsKnownDomains
// checks the published feed when VERITAS_MALWARE_FEED_LIVE=1.
var malwareFeedSamples = []string{
	"agenttesla.com",
	"amos-malware.ru",
	"payload-delivery.online",
	"0dayexploits.net",
}

type healthObserver struct {
	noopObserver
	refreshFailed int
	failed        map[string]int
	degraded      map[string]bool
	refreshed     int
}

func (o *healthObserver) DNSBlocklistRefreshFailed() { o.refreshFailed++ }

func (o *healthObserver) DNSBlocklistFeedHealth(failed map[string]int, degraded map[string]bool) {
	o.failed = failed
	o.degraded = degraded
}

func (o *healthObserver) DNSBlocklistRefreshed(int, time.Time) { o.refreshed++ }

func testLogger(t *testing.T) *logging.Logger {
	t.Helper()
	log, err := logging.New("error")
	if err != nil {
		t.Fatal(err)
	}
	return log
}

func TestDefaultMalwareFeedsAreHaGeZiMirrors(t *testing.T) {
	t.Setenv("DNS_SHIELD_CATEGORIES", "")
	t.Setenv("DNS_BLOCKLIST_URLS", "")
	t.Setenv("DNS_BLOCKLIST_URLS_MALWARE", "")
	_, urls := LoadShieldSourcesFromEnv()
	got := urls[CategoryMalware]
	if len(got) != 2 {
		t.Fatalf("malware feeds = %#v", got)
	}
	for _, raw := range got {
		if !strings.HasPrefix(raw, "https://") {
			t.Fatalf("malware feed must be https, got %q", raw)
		}
		if strings.Contains(raw, "urlhaus.abuse.ch") {
			t.Fatalf("dead URLhaus download must not be configured, got %q", raw)
		}
		if !strings.Contains(raw, "tif.mini-onlydomains.txt") {
			t.Fatalf("expected HaGeZi TIF mini domains feed, got %q", raw)
		}
	}
	if got[0] == got[1] {
		t.Fatal("malware feeds should be two mirrors, not the same URL twice")
	}
}

func TestMalwareFeedSamplesLandInMalwareCategory(t *testing.T) {
	body := "# Title: HaGeZi's Threat Intelligence Feeds\n" + strings.Join(malwareFeedSamples, "\n") + "\n"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("User-Agent") != "VeritasVPN-Shield/1.0" {
			http.Error(w, "bad user agent", http.StatusBadRequest)
			return
		}
		fmt.Fprint(w, body)
	}))
	t.Cleanup(srv.Close)

	obs := &healthObserver{}
	b := NewShieldBlocklist([]string{CategoryMalware}, map[string][]string{
		CategoryMalware: {srv.URL + "/mirror-a", srv.URL + "/mirror-b"},
	}, t.TempDir()+"/blocklist.txt", time.Hour, obs, testLogger(t))
	b.client = srv.Client()
	b.refresh(context.Background())

	for _, domain := range malwareFeedSamples {
		cat, ok := b.BlockedCategory(domain)
		if !ok || cat != CategoryMalware {
			t.Fatalf("%s category=%q ok=%v", domain, cat, ok)
		}
		if cat, ok := b.BlockedCategory("cdn." + domain); !ok || cat != CategoryMalware {
			t.Fatalf("subdomain of %s category=%q ok=%v", domain, cat, ok)
		}
	}
	if obs.refreshFailed != 0 || obs.refreshed != 1 {
		t.Fatalf("refreshFailed=%d refreshed=%d", obs.refreshFailed, obs.refreshed)
	}
	if obs.degraded[CategoryMalware] {
		t.Fatal("two live mirrors must not mark malware degraded")
	}
	if obs.failed[CategoryMalware] != 0 {
		t.Fatalf("duplicate mirror must not count as a feed failure: %#v", obs.failed)
	}
	cached, err := os.ReadFile(b.statePath)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(cached), feedStatePrefix+"malware "+srv.URL+"/mirror-a\n") {
		t.Fatalf("state file missing per-feed header:\n%s", cached)
	}

	reloaded := NewShieldBlocklist([]string{CategoryMalware}, nil, b.statePath, time.Hour, nil, testLogger(t))
	if err := reloaded.loadState(); err != nil {
		t.Fatal(err)
	}
	if cat, ok := reloaded.BlockedCategory("agenttesla.com"); !ok || cat != CategoryMalware {
		t.Fatalf("reloaded category=%q ok=%v", cat, ok)
	}
	if len(reloaded.feedCache) != 2 {
		t.Fatalf("reloaded feed cache = %d", len(reloaded.feedCache))
	}
}

func TestRefreshKeepsCachedMalwareWhenThatFeedFails(t *testing.T) {
	gone := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "gone", http.StatusGone)
	}))
	t.Cleanup(gone.Close)
	phish := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintln(w, "fresh-phish.example")
	}))
	t.Cleanup(phish.Close)

	state := filepath.Join(t.TempDir(), "blocklist.txt")
	legacy := "# veritas-shield-category:malware\ncached-malware.example\nagenttesla.com\n"
	if err := os.WriteFile(state, []byte(legacy), 0o600); err != nil {
		t.Fatal(err)
	}
	obs := &healthObserver{}
	b := NewShieldBlocklist([]string{CategoryMalware, CategoryPhishing}, map[string][]string{
		CategoryMalware:  {gone.URL},
		CategoryPhishing: {phish.URL},
	}, state, time.Hour, obs, testLogger(t))
	b.client = &http.Client{Timeout: 5 * time.Second}
	if err := b.loadState(); err != nil {
		t.Fatal(err)
	}
	obs.refreshed = 0
	b.mu.Lock()
	b.lastSuccess = time.Unix(100, 0).UTC()
	b.mu.Unlock()
	b.refresh(context.Background())

	for _, domain := range []string{"cached-malware.example", "agenttesla.com"} {
		if cat, ok := b.BlockedCategory(domain); !ok || cat != CategoryMalware {
			t.Fatalf("cached %s category=%q ok=%v", domain, cat, ok)
		}
	}
	if cat, ok := b.BlockedCategory("fresh-phish.example"); !ok || cat != CategoryPhishing {
		t.Fatalf("phishing category=%q ok=%v", cat, ok)
	}
	if !obs.degraded[CategoryMalware] || obs.degraded[CategoryPhishing] {
		t.Fatalf("degraded=%v", obs.degraded)
	}
	if obs.failed[CategoryMalware] != 1 || obs.refreshFailed != 0 || obs.refreshed != 1 {
		t.Fatalf("failed=%v refreshFailed=%d refreshed=%d", obs.failed, obs.refreshFailed, obs.refreshed)
	}
	b.mu.RLock()
	refreshed := b.lastSuccess
	b.mu.RUnlock()
	if !refreshed.After(time.Unix(100, 0)) {
		t.Fatal("a sibling feed succeeding should record a refresh")
	}
	if _, ok := b.feedCache[gone.URL]; !ok {
		t.Fatal("failed feed should keep a per-url cache for the next outage")
	}
}

func TestRefreshEmptyFeedUsesPerFeedCache(t *testing.T) {
	var calls int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if calls == 1 {
			fmt.Fprintln(w, "agenttesla.com")
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(srv.Close)
	obs := &healthObserver{}
	b := NewShieldBlocklist([]string{CategoryMalware}, map[string][]string{
		CategoryMalware: {srv.URL},
	}, "", time.Hour, obs, testLogger(t))
	b.client = srv.Client()
	b.refresh(context.Background())
	b.refresh(context.Background())
	if cat, ok := b.BlockedCategory("agenttesla.com"); !ok || cat != CategoryMalware {
		t.Fatalf("empty feed dropped the cached domain category=%q ok=%v", cat, ok)
	}
	if !obs.degraded[CategoryMalware] || obs.failed[CategoryMalware] != 1 {
		t.Fatalf("degraded=%v failed=%v", obs.degraded, obs.failed)
	}
	if obs.refreshFailed != 1 {
		t.Fatalf("total failure should increment refresh failures, got %d", obs.refreshFailed)
	}
}

func TestRefreshAllFeedsFailKeepsPreviousPolicy(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "gone", http.StatusGone)
	}))
	t.Cleanup(srv.Close)
	obs := &healthObserver{}
	b := NewShieldBlocklist([]string{CategoryMalware}, map[string][]string{
		CategoryMalware: {srv.URL},
	}, "", time.Hour, obs, testLogger(t))
	b.client = srv.Client()
	stamp := time.Unix(100, 0).UTC()
	b.replace(map[string]string{"keep.example": CategoryMalware}, map[string]int{CategoryMalware: 1}, stamp)
	b.refresh(context.Background())
	if cat, ok := b.BlockedCategory("keep.example"); !ok || cat != CategoryMalware {
		t.Fatalf("previous policy lost category=%q ok=%v", cat, ok)
	}
	b.mu.RLock()
	got := b.lastSuccess
	b.mu.RUnlock()
	if !got.Equal(stamp) {
		t.Fatalf("total failure moved last success to %s", got)
	}
	if obs.refreshFailed != 1 || !obs.degraded[CategoryMalware] {
		t.Fatalf("refreshFailed=%d degraded=%v", obs.refreshFailed, obs.degraded)
	}
}

func TestLiveMalwareFeedContainsKnownDomains(t *testing.T) {
	if os.Getenv("VERITAS_MALWARE_FEED_LIVE") == "" {
		t.Skip("set VERITAS_MALWARE_FEED_LIVE=1 to fetch the malware feeds")
	}
	client := &http.Client{Timeout: 60 * time.Second}
	for _, raw := range defaultCategoryURLs[CategoryMalware] {
		request, err := http.NewRequest(http.MethodGet, raw, nil)
		if err != nil {
			t.Fatal(err)
		}
		request.Header.Set("User-Agent", "VeritasVPN-Shield/1.0")
		response, err := client.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != http.StatusOK {
			response.Body.Close()
			t.Fatalf("%s status %d", raw, response.StatusCode)
		}
		parsed := map[string]struct{}{}
		n, err := parseBlocklist(response.Body, parsed)
		response.Body.Close()
		if err != nil {
			t.Fatal(err)
		}
		t.Logf("%s parsed_domains=%d", raw, n)
		if n < 100000 {
			t.Fatalf("%s parsed only %d domains", raw, n)
		}
		for _, domain := range malwareFeedSamples {
			if _, ok := parsed[domain]; !ok {
				t.Fatalf("%s missing %s", raw, domain)
			}
		}
		b := NewShieldBlocklist([]string{CategoryMalware}, nil, "", time.Hour, nil, testLogger(t))
		merged := map[string]string{}
		addBuiltInProtectionDomains(merged)
		mergeFeedDomains(merged, parsed, CategoryMalware)
		b.replace(merged, map[string]int{CategoryMalware: n}, time.Now().UTC())
		for _, domain := range malwareFeedSamples {
			if cat, ok := b.BlockedCategory(domain); !ok || cat != CategoryMalware {
				t.Fatalf("%s live category=%q ok=%v", domain, cat, ok)
			}
		}
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
