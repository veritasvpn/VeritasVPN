package dns

import (
	"bufio"
	"context"
	"fmt"
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

	// ProtectedDNSTestDomain is a harmless, reserved name that lets an
	// administrator verify the DNS protection path without visiting a real
	// malicious website. It is deliberately included in every policy version.
	ProtectedDNSTestDomain = "dns-protection-test.veritasvpn.invalid"
)

// Observer intentionally exposes aggregate events only. Implementations must
// never receive a queried domain or a client address.
type Observer interface {
	DNSQuery(blocked bool)
	DNSUpstreamFailure()
	DNSBlocklistRefreshed(domains int, at time.Time)
	DNSBlocklistRefreshFailed()
}

type noopObserver struct{}

func (noopObserver) DNSQuery(bool)                        {}
func (noopObserver) DNSUpstreamFailure()                  {}
func (noopObserver) DNSBlocklistRefreshed(int, time.Time) {}
func (noopObserver) DNSBlocklistRefreshFailed()           {}

type Blocklist struct {
	sources      []string
	statePath    string
	refreshEvery time.Duration
	client       *http.Client
	log          *logging.Logger
	observer     Observer

	mu          sync.RWMutex
	domains     map[string]struct{}
	lastSuccess time.Time
}

func NewBlocklist(sourceList, statePath string, refreshEvery time.Duration, observer Observer, log *logging.Logger) *Blocklist {
	if observer == nil {
		observer = noopObserver{}
	}
	if refreshEvery <= 0 {
		refreshEvery = 6 * time.Hour
	}
	domains := make(map[string]struct{}, len(builtInDoHBypassDomains)+1)
	addBuiltInProtectionDomains(domains)
	return &Blocklist{
		sources:      splitSources(sourceList),
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

func (b *Blocklist) Start(ctx context.Context) {
	if err := b.loadState(); err != nil && !os.IsNotExist(err) {
		b.log.Warn("load DNS blocklist cache failed", zap.Error(err))
	}
	if len(b.sources) == 0 {
		b.log.Warn("DNS security blocklist is disabled because no sources were configured")
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
	ctx, cancel := context.WithTimeout(parent, blocklistFetchTimeout*time.Duration(len(b.sources)))
	defer cancel()

	next := make(map[string]struct{})
	addBuiltInProtectionDomains(next)
	loadedSources := 0
	for _, source := range b.sources {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, source, nil)
		if err != nil {
			continue
		}
		request.Header.Set("User-Agent", "VeritasVPN-DNS-Protection/1.0")
		response, err := b.client.Do(request)
		if err != nil {
			b.log.Warn("DNS blocklist source unavailable", zap.Error(err))
			continue
		}
		if response.StatusCode != http.StatusOK {
			response.Body.Close()
			b.log.Warn("DNS blocklist source returned non-success status", zap.Int("status", response.StatusCode))
			continue
		}
		count, err := parseBlocklist(response.Body, next)
		response.Body.Close()
		if err != nil {
			b.log.Warn("DNS blocklist source could not be parsed", zap.Error(err))
			continue
		}
		if count > 0 {
			loadedSources++
		}
	}
	if loadedSources == 0 || len(next) == 0 {
		b.observer.DNSBlocklistRefreshFailed()
		b.log.Warn("DNS blocklist refresh failed; retaining the last known-good policy")
		return
	}

	now := time.Now().UTC()
	b.replace(next, now)
	if err := b.writeState(next, now); err != nil {
		b.log.Warn("persist DNS blocklist cache failed", zap.Error(err))
	}
	b.log.Info("DNS malware/phishing blocklist refreshed", zap.Int("domains", len(next)), zap.Int("sources", loadedSources))
}

func parseBlocklist(body interface{ Read([]byte) (int, error) }, target map[string]struct{}) (int, error) {
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
	name = strings.TrimSuffix(strings.ToLower(name), ".")
	b.mu.RLock()
	defer b.mu.RUnlock()
	for {
		if _, found := b.domains[name]; found {
			return true
		}
		separator := strings.IndexByte(name, '.')
		if separator < 0 {
			return false
		}
		name = name[separator+1:]
	}
}

func (b *Blocklist) replace(domains map[string]struct{}, refreshed time.Time) {
	b.mu.Lock()
	b.domains = domains
	b.lastSuccess = refreshed
	b.mu.Unlock()
	b.observer.DNSBlocklistRefreshed(len(domains), refreshed)
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
	domains := make(map[string]struct{})
	if _, err := parseBlocklist(file, domains); err != nil {
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
	b.replace(domains, info.ModTime().UTC())
	b.log.Info("loaded cached DNS malware/phishing blocklist", zap.Int("domains", len(domains)))
	return nil
}

func addBuiltInProtectionDomains(domains map[string]struct{}) {
	domains[ProtectedDNSTestDomain] = struct{}{}
	addBuiltInDoHBypassDomains(domains)
}

func (b *Blocklist) writeState(domains map[string]struct{}, _ time.Time) error {
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
	for domain := range domains {
		if _, err := writer.WriteString(domain + "\n"); err != nil {
			file.Close()
			return err
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
