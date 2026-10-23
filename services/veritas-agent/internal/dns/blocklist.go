package dns

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/veritasvpn/lib/logging"
	"go.uber.org/zap"
)

const (
	blocklistFetchTimeout = 20 * time.Second
	categoryStatePrefix   = "# veritas-shield-category:"
	// feedStatePrefix marks a per-feed cache section. The rest of the line is
	// "<category> <url>" so one dead feed can be restored without dropping
	// every other category. Older files only have categoryStatePrefix.
	feedStatePrefix = "# veritas-shield-feed:"

	// ProtectedDNSTestDomain is a harmless, reserved name in the malware
	// category. It returns NXDOMAIN when Block malicious sites is on (the
	// default) without visiting a real malicious website.
	ProtectedDNSTestDomain = "dns-protection-test.veritasvpn.invalid"
	// AdultProtectionTestDomain is a reserved name in the adult category.
	// It resolves unless Block adult sites is enabled for that peer.
	AdultProtectionTestDomain = "adult-protection-test.veritasvpn.invalid"
)

// Observer intentionally exposes aggregate events only. Implementations must
// never receive a queried domain or a client address.
type Observer interface {
	DNSQuery(blocked bool)
	DNSBlockedCategory(category string)
	DNSUpstreamFailure()
	DNSBlocklistRefreshed(domains int, at time.Time)
	DNSBlocklistCategorySizes(byCategory map[string]int)
	DNSBlocklistRefreshFailed()
	// DNSBlocklistFeedHealth reports the latest refresh. failed is how many
	// feeds in that category did not return domains. degraded is true when
	// every configured feed for that category failed (cache may still be
	// serving). Categories with no configured feed are omitted.
	DNSBlocklistFeedHealth(failed map[string]int, degraded map[string]bool)
}

type noopObserver struct{}

func (noopObserver) DNSQuery(bool)                                          {}
func (noopObserver) DNSBlockedCategory(string)                              {}
func (noopObserver) DNSUpstreamFailure()                                    {}
func (noopObserver) DNSBlocklistRefreshed(int, time.Time)                   {}
func (noopObserver) DNSBlocklistCategorySizes(map[string]int)               {}
func (noopObserver) DNSBlocklistRefreshFailed()                             {}
func (noopObserver) DNSBlocklistFeedHealth(map[string]int, map[string]bool) {}

type Blocklist struct {
	categories   []string
	sources      map[string][]string // category → HTTPS feeds
	statePath    string
	refreshEvery time.Duration
	client       *http.Client
	log          *logging.Logger
	observer     Observer

	mu          sync.RWMutex
	domains     map[string]string // domain → category (first enabled category wins)
	lastSuccess time.Time

	// feedCache is the last non-empty download for each source URL.
	// legacyCache is a pre-per-feed state file, keyed by category, used only
	// when that category's URLs have no feedCache entry yet.
	feedCache   map[string]feedCacheEntry
	legacyCache map[string]map[string]struct{}
}

type feedCacheEntry struct {
	category string
	domains  map[string]struct{}
}

// NewBlocklist builds a Shield blocklist. Prefer NewShieldBlocklist.
// legacyURLs alone enables malware+phishing with those feeds (backward compatible).
func NewBlocklist(legacyURLs, statePath string, refreshEvery time.Duration, observer Observer, log *logging.Logger) *Blocklist {
	cats := append([]string(nil), DefaultShieldCategories...)
	urls := map[string][]string{}
	legacy := splitSources(legacyURLs)
	if len(legacy) > 0 {
		cats = []string{CategoryMalware, CategoryPhishing}
		urls[CategoryMalware] = append([]string(nil), legacy...)
		urls[CategoryPhishing] = append([]string(nil), legacy...)
	} else {
		for _, c := range cats {
			if defs := defaultCategoryURLs[c]; len(defs) > 0 {
				urls[c] = append([]string(nil), defs...)
			}
		}
	}
	return NewShieldBlocklist(cats, urls, statePath, refreshEvery, observer, log)
}

// NewShieldBlocklist constructs a categorized Veritas Shield policy.
func NewShieldBlocklist(categories []string, urls map[string][]string, statePath string, refreshEvery time.Duration, observer Observer, log *logging.Logger) *Blocklist {
	if observer == nil {
		observer = noopObserver{}
	}
	if refreshEvery <= 0 {
		refreshEvery = 6 * time.Hour
	}
	if urls == nil {
		urls = map[string][]string{}
	}
	cleanCats := make([]string, 0, len(categories))
	seen := map[string]struct{}{}
	for _, c := range categories {
		c = strings.ToLower(strings.TrimSpace(c))
		if _, ok := knownCategories[c]; !ok {
			continue
		}
		if _, dup := seen[c]; dup {
			continue
		}
		seen[c] = struct{}{}
		cleanCats = append(cleanCats, c)
	}
	domains := make(map[string]string, len(builtInDoHBypassDomains)+8)
	addBuiltInProtectionDomains(domains)
	return &Blocklist{
		categories:   cleanCats,
		sources:      urls,
		statePath:    statePath,
		refreshEvery: refreshEvery,
		client:       &http.Client{Timeout: blocklistFetchTimeout},
		log:          log,
		observer:     observer,
		domains:      domains,
		feedCache:    map[string]feedCacheEntry{},
		legacyCache:  map[string]map[string]struct{}{},
	}
}

func splitSources(raw string) []string {
	parts := strings.FieldsFunc(raw, func(r rune) bool { return r == ',' || r == ';' || r == ' ' || r == '\n' })
	var out []string
	for _, part := range parts {
		if strings.HasPrefix(part, "https://") {
			out = append(out, part)
		}
	}
	return out
}

func (b *Blocklist) sourceCount() int {
	n := 0
	for _, cat := range b.categories {
		n += len(b.sources[cat])
	}
	return n
}

func (b *Blocklist) Start(ctx context.Context) {
	if err := b.loadState(); err != nil && !os.IsNotExist(err) {
		b.log.Warn("load DNS blocklist cache failed", zap.Error(err))
	}
	if b.sourceCount() == 0 {
		b.log.Warn("Veritas Shield blocklist feeds are empty; built-in protections only")
		return
	}
	go func() {
		b.refresh(ctx)
		ticker := time.NewTicker(b.refreshEvery)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				b.refresh(ctx)
			}
		}
	}()
}

func (b *Blocklist) refresh(parent context.Context) {
	totalFeeds := b.sourceCount()
	if totalFeeds == 0 {
		return
	}
	ctx, cancel := context.WithTimeout(parent, blocklistFetchTimeout*time.Duration(totalFeeds))
	defer cancel()

	next := make(map[string]string)
	addBuiltInProtectionDomains(next)
	byCategory := map[string]int{}
	fresh := map[string]int{}
	failed := map[string]int{}
	nextCache := make(map[string]feedCacheEntry, totalFeeds)
	legacyUsed := map[string]bool{}

	for _, cat := range b.categories {
		for _, source := range b.sources[cat] {
			parsed, err := b.fetchFeedDomains(ctx, source)
			if err != nil {
				failed[cat]++
				b.logError("DNS blocklist feed failed",
					zap.String("category", cat),
					zap.String("source", source),
					zap.Error(err),
				)
				if restored, ok := b.cachedFeedDomains(cat, source, legacyUsed); ok {
					byCategory[cat] += mergeFeedDomains(next, restored, cat)
					nextCache[source] = feedCacheEntry{category: cat, domains: restored}
					b.logError("DNS blocklist feed is serving its last good copy",
						zap.String("category", cat),
						zap.String("source", source),
						zap.Int("domains", len(restored)),
					)
				}
				continue
			}
			fresh[cat]++
			byCategory[cat] += mergeFeedDomains(next, parsed, cat)
			nextCache[source] = feedCacheEntry{category: cat, domains: parsed}
		}
	}

	degraded := map[string]bool{}
	freshTotal := 0
	for _, cat := range b.categories {
		if len(b.sources[cat]) == 0 {
			continue
		}
		freshTotal += fresh[cat]
		degraded[cat] = fresh[cat] == 0
	}
	b.observer.DNSBlocklistFeedHealth(failed, degraded)

	if freshTotal == 0 {
		b.observer.DNSBlocklistRefreshFailed()
		if len(nextCache) == 0 {
			b.logError("Veritas Shield blocklist refresh failed; retaining the last known-good policy")
			return
		}
		// Keep the previous success timestamp so a total outage still looks stale.
		b.feedCache = nextCache
		b.mu.Lock()
		b.domains = next
		b.mu.Unlock()
		b.observer.DNSBlocklistCategorySizes(byCategory)
		b.logError("Veritas Shield blocklist refresh failed; serving per-feed cached copies",
			zap.Any("by_category", byCategory),
		)
		return
	}

	b.feedCache = nextCache
	b.legacyCache = map[string]map[string]struct{}{}
	now := time.Now().UTC()
	b.replace(next, byCategory, now)
	if err := b.writeState(); err != nil {
		b.logWarn("persist DNS blocklist cache failed", zap.Error(err))
	}
	b.logInfo("Veritas Shield blocklist refreshed",
		zap.Int("domains", len(next)),
		zap.Int("sources", freshTotal),
		zap.Any("by_category", byCategory),
		zap.Any("degraded", degraded),
	)
}

func (b *Blocklist) cachedFeedDomains(category, source string, legacyUsed map[string]bool) (map[string]struct{}, bool) {
	if entry, ok := b.feedCache[source]; ok && entry.category == category && len(entry.domains) > 0 {
		return entry.domains, true
	}
	if legacyUsed[category] || len(b.legacyCache[category]) == 0 {
		return nil, false
	}
	legacyUsed[category] = true
	return b.legacyCache[category], true
}

func (b *Blocklist) fetchFeedDomains(ctx context.Context, source string) (map[string]struct{}, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, source, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("User-Agent", "VeritasVPN-Shield/1.0")
	response, err := b.client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("status %d", response.StatusCode)
	}
	parsed := make(map[string]struct{})
	added, err := parseBlocklist(response.Body, parsed)
	if err != nil {
		return nil, err
	}
	if added == 0 {
		return nil, fmt.Errorf("feed contained no domains")
	}
	return parsed, nil
}

func mergeFeedDomains(target map[string]string, domains map[string]struct{}, category string) int {
	added := 0
	for domain := range domains {
		if _, exists := target[domain]; exists {
			continue
		}
		target[domain] = category
		added++
	}
	return added
}

func (b *Blocklist) logError(msg string, fields ...interface{}) {
	if b.log != nil {
		b.log.Error(msg, fields...)
	}
}

func (b *Blocklist) logWarn(msg string, fields ...interface{}) {
	if b.log != nil {
		b.log.Warn(msg, fields...)
	}
}

func (b *Blocklist) logInfo(msg string, fields ...interface{}) {
	if b.log != nil {
		b.log.Info(msg, fields...)
	}
}

func parseBlocklist(body io.Reader, target map[string]struct{}) (int, error) {
	scanner := bufio.NewScanner(body)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	before := len(target)
	for scanner.Scan() {
		if domain := domainFromBlocklistLine(scanner.Text()); domain != "" {
			target[domain] = struct{}{}
		}
	}
	return len(target) - before, scanner.Err()
}

// parseBlocklistCategory inserts domains; existing entries keep their category
// (first enabled category in refresh order wins).
func parseBlocklistCategory(body io.Reader, target map[string]string, category string) (int, error) {
	scanner := bufio.NewScanner(body)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	added := 0
	for scanner.Scan() {
		domain := domainFromBlocklistLine(scanner.Text())
		if domain == "" {
			continue
		}
		if _, exists := target[domain]; exists {
			continue
		}
		target[domain] = category
		added++
	}
	return added, scanner.Err()
}

func domainFromBlocklistLine(line string) string {
	line = strings.TrimSpace(line)
	if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "!") {
		return ""
	}
	if index := strings.Index(line, "#"); index >= 0 {
		line = strings.TrimSpace(line[:index])
	}
	if strings.HasPrefix(line, "||") {
		line = strings.TrimPrefix(line, "||")
		line = strings.TrimSuffix(line, "^")
	}
	fields := strings.Fields(line)
	if len(fields) > 1 && (fields[0] == "0.0.0.0" || fields[0] == "127.0.0.1" || fields[0] == "::") {
		line = fields[1]
	} else if len(fields) == 1 {
		line = fields[0]
	} else {
		return ""
	}
	line = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(line)), ".")
	if !validDomain(line) {
		return ""
	}
	return line
}

func validDomain(domain string) bool {
	if len(domain) == 0 || len(domain) > 253 || !strings.Contains(domain, ".") || strings.Contains(domain, "/") {
		return false
	}
	for _, label := range strings.Split(domain, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, r := range label {
			if (r < 'a' || r > 'z') && (r < '0' || r > '9') && r != '-' {
				return false
			}
		}
	}
	return true
}

func (b *Blocklist) Blocked(name string) bool {
	_, ok := b.BlockedCategory(name)
	return ok
}

// BlockedCategory returns the Shield category for a query name (suffix match).
func (b *Blocklist) BlockedCategory(name string) (string, bool) {
	name = strings.TrimSuffix(strings.ToLower(name), ".")
	b.mu.RLock()
	defer b.mu.RUnlock()
	for {
		if cat, found := b.domains[name]; found {
			return cat, true
		}
		separator := strings.IndexByte(name, '.')
		if separator < 0 {
			return "", false
		}
		name = name[separator+1:]
	}
}

func (b *Blocklist) replace(domains map[string]string, byCategory map[string]int, refreshed time.Time) {
	b.mu.Lock()
	b.domains = domains
	b.lastSuccess = refreshed
	b.mu.Unlock()
	b.observer.DNSBlocklistRefreshed(len(domains), refreshed)
	if byCategory == nil {
		byCategory = map[string]int{}
	}
	b.observer.DNSBlocklistCategorySizes(byCategory)
}

func (b *Blocklist) loadState() error {
	if b.statePath == "" {
		return os.ErrNotExist
	}
	file, err := os.Open(b.statePath)
	if err != nil {
		return err
	}
	defer file.Close()

	domains := make(map[string]string)
	byCategory := map[string]int{}
	feeds := map[string]feedCacheEntry{}
	legacy := map[string]map[string]struct{}{}
	currentCat := CategoryMalware
	currentSource := ""
	feedSection := false
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if strings.HasPrefix(line, feedStatePrefix) {
			cat, source, ok := parseFeedStateHeader(line)
			if !ok {
				feedSection = false
				currentSource = ""
				continue
			}
			currentCat = cat
			currentSource = source
			feedSection = true
			if _, exists := feeds[source]; !exists {
				feeds[source] = feedCacheEntry{category: cat, domains: map[string]struct{}{}}
			}
			continue
		}
		if strings.HasPrefix(line, categoryStatePrefix) {
			currentCat = strings.ToLower(strings.TrimSpace(strings.TrimPrefix(line, categoryStatePrefix)))
			if _, ok := knownCategories[currentCat]; !ok {
				currentCat = CategoryMalware
			}
			if !feedSection {
				currentSource = ""
			}
			continue
		}
		domain := domainFromBlocklistLine(line)
		if domain == "" {
			continue
		}
		if feedSection && currentSource != "" {
			feeds[currentSource].domains[domain] = struct{}{}
		} else {
			if legacy[currentCat] == nil {
				legacy[currentCat] = map[string]struct{}{}
			}
			legacy[currentCat][domain] = struct{}{}
		}
		if _, exists := domains[domain]; exists {
			continue
		}
		domains[domain] = currentCat
		byCategory[currentCat]++
	}
	if err := scanner.Err(); err != nil {
		return err
	}
	b.feedCache = feeds
	b.legacyCache = legacy
	addBuiltInProtectionDomains(domains)
	if len(domains) == 0 {
		return fmt.Errorf("cached DNS blocklist is empty")
	}
	info, err := file.Stat()
	if err != nil {
		return err
	}
	b.replace(domains, byCategory, info.ModTime().UTC())
	b.logInfo("loaded cached Veritas Shield blocklist", zap.Int("domains", len(domains)), zap.Any("by_category", byCategory))
	return nil
}

func addBuiltInProtectionDomains(domains map[string]string) {
	if _, ok := domains[ProtectedDNSTestDomain]; !ok {
		domains[ProtectedDNSTestDomain] = CategoryMalware
	}
	if _, ok := domains[AdultProtectionTestDomain]; !ok {
		domains[AdultProtectionTestDomain] = CategoryAdult
	}
	for _, d := range builtInDoHBypassDomains {
		if _, ok := domains[d]; !ok {
			domains[d] = CategoryMalware
		}
	}
}

func parseFeedStateHeader(line string) (category, source string, ok bool) {
	rest := strings.TrimSpace(strings.TrimPrefix(line, feedStatePrefix))
	category, source, found := strings.Cut(rest, " ")
	category = strings.ToLower(strings.TrimSpace(category))
	source = strings.TrimSpace(source)
	if !found || source == "" {
		return "", "", false
	}
	if _, known := knownCategories[category]; !known {
		return "", "", false
	}
	return category, source, true
}

func (b *Blocklist) categoryRank(category string) int {
	for i, cat := range b.categories {
		if cat == category {
			return i
		}
	}
	order := []string{CategoryMalware, CategoryPhishing, CategoryScam, CategoryCrypto, CategoryTrackers, CategoryAds, CategoryAdult}
	for i, cat := range order {
		if cat == category {
			return 100 + i
		}
	}
	return 1000
}

func (b *Blocklist) writeState() error {
	if b.statePath == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(b.statePath), 0700); err != nil {
		return err
	}
	temporary := b.statePath + ".tmp"
	file, err := os.OpenFile(temporary, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	writer := bufio.NewWriter(file)

	type item struct {
		source string
		entry  feedCacheEntry
	}
	items := make([]item, 0, len(b.feedCache))
	for source, entry := range b.feedCache {
		if entry.category == "" || len(entry.domains) == 0 {
			continue
		}
		items = append(items, item{source: source, entry: entry})
	}
	sort.Slice(items, func(i, j int) bool {
		if items[i].entry.category != items[j].entry.category {
			return b.categoryRank(items[i].entry.category) < b.categoryRank(items[j].entry.category)
		}
		return items[i].source < items[j].source
	})
	for _, it := range items {
		if _, err := writer.WriteString(feedStatePrefix + it.entry.category + " " + it.source + "\n"); err != nil {
			file.Close()
			return err
		}
		if _, err := writer.WriteString(categoryStatePrefix + it.entry.category + "\n"); err != nil {
			file.Close()
			return err
		}
		domains := make([]string, 0, len(it.entry.domains))
		for domain := range it.entry.domains {
			domains = append(domains, domain)
		}
		sort.Strings(domains)
		for _, domain := range domains {
			if _, err := writer.WriteString(domain + "\n"); err != nil {
				file.Close()
				return err
			}
		}
	}
	if err := writer.Flush(); err != nil {
		file.Close()
		return err
	}
	if err := file.Sync(); err != nil {
		file.Close()
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	return os.Rename(temporary, b.statePath)
}
