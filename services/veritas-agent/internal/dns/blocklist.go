package dns

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/veritasvpn/lib/logging"
	"go.uber.org/zap"
)

const (
	blocklistFetchTimeout = 20 * time.Second
	categoryStatePrefix   = "# veritas-shield-category:"

	// ProtectedDNSTestDomain is a harmless, reserved name that lets an
	// administrator verify the DNS protection path without visiting a real
	// malicious website. It is deliberately included in every policy version.
	ProtectedDNSTestDomain = "dns-protection-test.veritasvpn.invalid"
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
}

type noopObserver struct{}

func (noopObserver) DNSQuery(bool)                              {}
func (noopObserver) DNSBlockedCategory(string)                  {}
func (noopObserver) DNSUpstreamFailure()                        {}
func (noopObserver) DNSBlocklistRefreshed(int, time.Time)       {}
func (noopObserver) DNSBlocklistCategorySizes(map[string]int)   {}
func (noopObserver) DNSBlocklistRefreshFailed()                 {}

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
			if def, ok := defaultCategoryURLs[c]; ok {
				urls[c] = []string{def}
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
	loadedSources := 0

	for _, cat := range b.categories {
		for _, source := range b.sources[cat] {
			request, err := http.NewRequestWithContext(ctx, http.MethodGet, source, nil)
			if err != nil {
				continue
			}
			request.Header.Set("User-Agent", "VeritasVPN-Shield/1.0")
			response, err := b.client.Do(request)
			if err != nil {
				b.log.Warn("DNS blocklist source unavailable", zap.String("category", cat), zap.Error(err))
				continue
			}
			if response.StatusCode != http.StatusOK {
				response.Body.Close()
				b.log.Warn("DNS blocklist source returned non-success status", zap.String("category", cat), zap.Int("status", response.StatusCode))
				continue
			}
			added, err := parseBlocklistCategory(response.Body, next, cat)
			response.Body.Close()
			if err != nil {
				b.log.Warn("DNS blocklist source could not be parsed", zap.String("category", cat), zap.Error(err))
				continue
			}
			if added > 0 {
				loadedSources++
				byCategory[cat] += added
			}
		}
	}

	if loadedSources == 0 {
		b.observer.DNSBlocklistRefreshFailed()
		b.log.Warn("Veritas Shield blocklist refresh failed; retaining the last known-good policy")
		return
	}

	now := time.Now().UTC()
	b.replace(next, byCategory, now)
	if err := b.writeState(next); err != nil {
		b.log.Warn("persist DNS blocklist cache failed", zap.Error(err))
	}
	b.log.Info("Veritas Shield blocklist refreshed",
		zap.Int("domains", len(next)),
		zap.Int("sources", loadedSources),
		zap.Any("by_category", byCategory),
	)
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
	currentCat := CategoryMalware
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if strings.HasPrefix(line, categoryStatePrefix) {
			currentCat = strings.ToLower(strings.TrimSpace(strings.TrimPrefix(line, categoryStatePrefix)))
			if _, ok := knownCategories[currentCat]; !ok {
				currentCat = CategoryMalware
			}
			continue
		}
		domain := domainFromBlocklistLine(line)
		if domain == "" {
			continue
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
	addBuiltInProtectionDomains(domains)
	if len(domains) == 0 {
		return fmt.Errorf("cached DNS blocklist is empty")
	}
	info, err := file.Stat()
	if err != nil {
		return err
	}
	b.replace(domains, byCategory, info.ModTime().UTC())
	b.log.Info("loaded cached Veritas Shield blocklist", zap.Int("domains", len(domains)), zap.Any("by_category", byCategory))
	return nil
}

func addBuiltInProtectionDomains(domains map[string]string) {
	if _, ok := domains[ProtectedDNSTestDomain]; !ok {
		domains[ProtectedDNSTestDomain] = CategoryMalware
	}
	for _, d := range builtInDoHBypassDomains {
		if _, ok := domains[d]; !ok {
			domains[d] = CategoryMalware
		}
	}
}

func (b *Blocklist) writeState(domains map[string]string) error {
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

	byCat := map[string][]string{}
	for domain, cat := range domains {
		byCat[cat] = append(byCat[cat], domain)
	}
	order := append([]string(nil), b.categories...)
	for _, extra := range []string{CategoryMalware, CategoryPhishing, CategoryScam, CategoryCrypto, CategoryTrackers, CategoryAds} {
		found := false
		for _, c := range order {
			if c == extra {
				found = true
				break
			}
		}
		if !found {
			order = append(order, extra)
		}
	}
	for _, cat := range order {
		list := byCat[cat]
		if len(list) == 0 {
			continue
		}
		if _, err := writer.WriteString(categoryStatePrefix + cat + "\n"); err != nil {
			file.Close()
			return err
		}
		for _, domain := range list {
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
