package dns

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
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
	// feedStatePrefix marks a per-feed cache file (and older combined state
	// files). The rest of the line is "<category> <url>".
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

	// One new map. The previous policy stays in b.domains until this one is
	// ready, so queries keep working. Per-feed copies live on disk; holding
	// them as maps beside this one (and the live policy) exceeded the 512Mi
	// cgroup. Two merged maps peak around 400Mi RSS; a porn-sized side map
	// on top of that pushed past 512Mi.
	//
	// Hint from the live policy so the replacement does not rehash while that
	// policy is still resident. A grow keeps the old and new bucket arrays
	// until the next GC, on top of the two live maps.
	b.mu.RLock()
	hint := len(b.domains)
	b.mu.RUnlock()
	if hint < 64 {
		hint = 64
	}
	next := make(map[string]string, hint)
	addBuiltInProtectionDomains(next)
	byCategory := map[string]int{}
	fresh := map[string]int{}
	failed := map[string]int{}
	restored := map[string]int{}

	for _, cat := range b.categories {
		var failedSources []string
		for _, source := range b.sources[cat] {
			_, added, err := b.fetchInto(ctx, source, cat, next)
			if err != nil {
				failed[cat]++
				b.logError("DNS blocklist feed failed",
					zap.String("category", cat),
					zap.String("source", source),
					zap.Error(err),
				)
				n, ok := b.restoreFeedFile(source, cat, next)
				if ok {
					byCategory[cat] += n
					restored[cat]++
					b.logError("DNS blocklist feed is serving its last good copy",
						zap.String("category", cat),
						zap.String("source", source),
						zap.Int("domains", n),
					)
					continue
				}
				failedSources = append(failedSources, source)
				continue
			}
			fresh[cat]++
			byCategory[cat] += added
		}
		if fresh[cat] == 0 && restored[cat] == 0 && len(b.sources[cat]) > 0 {
			n := b.restoreLegacyCategory(cat, next)
			if n > 0 {
				byCategory[cat] += n
				restored[cat] += n
				for _, source := range failedSources {
					if err := b.copyLegacyCategoryToFeed(cat, source); err != nil {
						b.logWarn("persist per-feed blocklist cache failed", zap.String("category", cat), zap.String("source", source), zap.Error(err))
					}
				}
			}
		}
	}

	degraded := map[string]bool{}
	freshTotal := 0
	restoredTotal := 0
	for _, cat := range b.categories {
		if len(b.sources[cat]) == 0 {
			continue
		}
		freshTotal += fresh[cat]
		restoredTotal += restored[cat]
		degraded[cat] = fresh[cat] == 0
	}
	b.observer.DNSBlocklistFeedHealth(failed, degraded)

	if freshTotal == 0 {
		b.observer.DNSBlocklistRefreshFailed()
		if restoredTotal == 0 {
			b.logError("Veritas Shield blocklist refresh failed; retaining the last known-good policy")
			return
		}
		// Keep the previous success timestamp so a total outage still looks stale.
		b.mu.Lock()
		b.domains = next
		b.mu.Unlock()
		b.releaseReplacedPolicy()
		b.observer.DNSBlocklistCategorySizes(byCategory)
		b.logError("Veritas Shield blocklist refresh failed; serving per-feed cached copies",
			zap.Any("by_category", byCategory),
		)
		return
	}

	now := time.Now().UTC()
	b.replace(next, byCategory, now)
	b.releaseReplacedPolicy()
	if err := b.writeState(next); err != nil {
		b.logWarn("persist DNS blocklist cache failed", zap.Error(err))
	}
	b.logInfo("Veritas Shield blocklist refreshed",
		zap.Int("domains", len(next)),
		zap.Int("sources", freshTotal),
		zap.Any("by_category", byCategory),
		zap.Any("degraded", degraded),
	)
}

// releaseReplacedPolicy returns the previous policy's heap to the OS. The
// next refresh would otherwise keep those idle spans and add another full
// map on top of them.
func (b *Blocklist) releaseReplacedPolicy() {
	runtime.GC()
	debug.FreeOSMemory()
}

func (b *Blocklist) fetchInto(ctx context.Context, source, category string, next map[string]string) (parsed, added int, err error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, source, nil)
	if err != nil {
		return 0, 0, err
	}
	request.Header.Set("User-Agent", "VeritasVPN-Shield/1.0")
	response, err := b.client.Do(request)
	if err != nil {
		return 0, 0, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return 0, 0, fmt.Errorf("status %d", response.StatusCode)
	}
	parsed, added, err = b.scanInto(response.Body, category, next, b.feedCachePath(source))
	if err != nil {
		return parsed, added, err
	}
	if parsed == 0 {
		return 0, 0, fmt.Errorf("feed contained no domains")
	}
	return parsed, added, nil
}

func (b *Blocklist) restoreFeedFile(source, category string, next map[string]string) (added int, ok bool) {
	path := b.feedCachePath(source)
	if path == "" {
		return 0, false
	}
	file, err := os.Open(path)
	if err != nil {
		return 0, false
	}
	defer file.Close()
	parsed, added, err := scanMerge(file, category, next, nil, false)
	if err != nil || parsed == 0 {
		return 0, false
	}
	return added, true
}

// restoreLegacyCategory streams a pre-per-feed state file and keeps only this
// category. Used when a feed has never been cached on disk.
func (b *Blocklist) restoreLegacyCategory(category string, next map[string]string) int {
	if b.statePath == "" {
		return 0
	}
	file, err := os.Open(b.statePath)
	if err != nil {
		return 0
	}
	defer file.Close()
	_, added, err := scanMerge(file, category, next, nil, true)
	if err != nil {
		b.logWarn("read cached DNS blocklist category failed", zap.String("category", category), zap.Error(err))
		return 0
	}
	return added
}

func (b *Blocklist) copyLegacyCategoryToFeed(category, source string) error {
	path := b.feedCachePath(source)
	if path == "" || b.statePath == "" {
		return nil
	}
	file, err := os.Open(b.statePath)
	if err != nil {
		return err
	}
	defer file.Close()
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	temporary := path + ".tmp"
	out, err := os.OpenFile(temporary, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	writer := bufio.NewWriter(out)
	if _, err := writer.WriteString(feedStatePrefix + category + " " + source + "\n"); err != nil {
		out.Close()
		os.Remove(temporary)
		return err
	}
	if _, _, err := scanMerge(file, category, nil, writer, true); err != nil {
		out.Close()
		os.Remove(temporary)
		return err
	}
	if err := writer.Flush(); err != nil {
		out.Close()
		os.Remove(temporary)
		return err
	}
	if err := out.Sync(); err != nil {
		out.Close()
		os.Remove(temporary)
		return err
	}
	if err := out.Close(); err != nil {
		os.Remove(temporary)
		return err
	}
	return os.Rename(temporary, path)
}

// feedCachePath is a file of hostnames for one source URL. Empty when the
// blocklist is not persisting state. The names are not kept in memory.
func (b *Blocklist) feedCachePath(source string) string {
	if b.statePath == "" || source == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(source))
	return filepath.Join(filepath.Dir(b.statePath), "feeds", hex.EncodeToString(sum[:])+".txt")
}

// scanInto merges hostnames into next. When cachePath is set, the feed's own
// names are also written there so a later failure can be streamed back.
func (b *Blocklist) scanInto(body io.Reader, category string, next map[string]string, cachePath string) (parsed, added int, err error) {
	var cache *os.File
	var writer *bufio.Writer
	temporary := ""
	if cachePath != "" {
		if err := os.MkdirAll(filepath.Dir(cachePath), 0700); err != nil {
			return 0, 0, err
		}
		temporary = cachePath + ".tmp"
		cache, err = os.OpenFile(temporary, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
		if err != nil {
			return 0, 0, err
		}
		writer = bufio.NewWriter(cache)
		if _, err := writer.WriteString(categoryStatePrefix + category + "\n"); err != nil {
			cache.Close()
			os.Remove(temporary)
			return 0, 0, err
		}
	}
	parsed, added, err = scanMerge(body, category, next, writer, false)
	if cache != nil {
		closeErr := writer.Flush()
		if err == nil {
			err = closeErr
		}
		if syncErr := cache.Sync(); err == nil {
			err = syncErr
		}
		if closeErr = cache.Close(); err == nil {
			err = closeErr
		}
		if err != nil || parsed == 0 {
			os.Remove(temporary)
			if err == nil && parsed == 0 {
				return 0, added, nil
			}
			return parsed, added, err
		}
		if err = os.Rename(temporary, cachePath); err != nil {
			os.Remove(temporary)
			// The names are already in next. Keep the refresh; the next
			// successful download will retry the file.
			b.logWarn("persist per-feed blocklist cache failed", zap.String("category", category), zap.Error(err))
			err = nil
		}
	}
	return parsed, added, err
}

// scanMerge reads hostnames. When respectSections is set, only domains under
// category's section of a combined state file are used. Otherwise every
// hostname is assigned to category (a single feed, or that feed's cache file).
// next may be nil to write names without inserting them.
func scanMerge(body io.Reader, category string, next map[string]string, writer *bufio.Writer, respectSections bool) (parsed, added int, err error) {
	current := CategoryMalware
	scanner := bufio.NewScanner(body)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if respectSections {
			if strings.HasPrefix(line, feedStatePrefix) {
				if cat, _, ok := parseFeedStateHeader(line); ok {
					current = cat
				}
				continue
			}
			if strings.HasPrefix(line, categoryStatePrefix) {
				cat := strings.ToLower(strings.TrimSpace(strings.TrimPrefix(line, categoryStatePrefix)))
				if _, ok := knownCategories[cat]; ok {
					current = cat
				}
				continue
			}
			if current != category {
				continue
			}
		}
		domain := domainFromBlocklistLine(line)
		if domain == "" {
			continue
		}
		parsed++
		if writer != nil {
			if _, err = writer.WriteString(domain + "\n"); err != nil {
				return parsed, added, err
			}
		}
		if next == nil {
			continue
		}
		if _, exists := next[domain]; exists {
			continue
		}
		next[domain] = category
		added++
	}
	return parsed, added, scanner.Err()
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
	currentCat := CategoryMalware
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if strings.HasPrefix(line, feedStatePrefix) {
			if cat, _, ok := parseFeedStateHeader(line); ok {
				currentCat = cat
			}
			continue
		}
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
	for _, extra := range []string{CategoryMalware, CategoryPhishing, CategoryScam, CategoryCrypto, CategoryTrackers, CategoryAds, CategoryAdult} {
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
		sort.Strings(list)
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
