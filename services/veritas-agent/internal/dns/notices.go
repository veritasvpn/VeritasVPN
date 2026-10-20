package dns

import (
	"crypto/sha256"
	"encoding/hex"
	"strings"
	"time"
)

const (
	noticeRingCap     = 8
	noticeMaxAge      = 90 * time.Second
	noticeDedupWindow = 10 * time.Minute
	noticeDedupCap    = 64
)

// DNSNotice is one blocked name held in memory so the app can show a notice.
// It is not written to logs or Prometheus.
type DNSNotice struct {
	ID       string `json:"id"`
	Domain   string `json:"domain"`
	Category string `json:"category"`
	At       int64  `json:"at"`
}

type dnsNotice struct {
	id       string
	domain   string
	category string
	at       time.Time
}

func noticeCategoryAllowed(category string) bool {
	switch category {
	case CategoryMalware, CategoryPhishing, CategoryScam, CategoryCrypto,
		CategoryTrackers, CategoryAds, CategoryAdult:
		return true
	default:
		return false
	}
}

func noticeIsMalicious(category string) bool {
	switch category {
	case CategoryMalware, CategoryPhishing, CategoryScam, CategoryCrypto:
		return true
	default:
		return false
	}
}

func noticeID(domain, category string, at time.Time) string {
	bucket := at.Unix() / int64(noticeDedupWindow/time.Second)
	sum := sha256.Sum256([]byte(domain + "|" + category + "|" + itoa(bucket)))
	return hex.EncodeToString(sum[:8])
}

func itoa(n int64) string {
	if n == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	neg := n < 0
	if neg {
		n = -n
	}
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

// noteBlock counts the block and maybe enqueues a short-lived notice.
// Counters increment on every query. Notices are deduped.
func (f *Forwarder) noteBlock(clientIP, domain, category string, now time.Time) {
	if f == nil {
		return
	}
	ip := stripHost(clientIP)
	domain = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(domain)), ".")
	category = strings.ToLower(strings.TrimSpace(category))
	if ip == "" || domain == "" || !noticeCategoryAllowed(category) {
		return
	}
	f.blockedMu.Lock()
	defer f.blockedMu.Unlock()
	if f.blockedByClient == nil {
		f.blockedByClient = make(map[string]uint64)
	}
	f.blockedByClient[ip]++
	if f.blockedByCategory == nil {
		f.blockedByCategory = make(map[string]map[string]uint64)
	}
	if f.blockedByCategory[ip] == nil {
		f.blockedByCategory[ip] = make(map[string]uint64)
	}
	f.blockedByCategory[ip][category]++
	f.enqueueNoticeLocked(ip, domain, category, now)
}

func (f *Forwarder) enqueueNoticeLocked(ip, domain, category string, now time.Time) {
	if f.noticeDedup == nil {
		f.noticeDedup = make(map[string]time.Time)
	}
	key := ip + "|" + domain + "|" + category
	if last, ok := f.noticeDedup[key]; ok && now.Sub(last) < noticeDedupWindow {
		return
	}
	f.noticeDedup[key] = now
	f.pruneDedupLocked(ip, now)

	if f.noticesByClient == nil {
		f.noticesByClient = make(map[string][]dnsNotice)
	}
	item := dnsNotice{
		id:       noticeID(domain, category, now),
		domain:   domain,
		category: category,
		at:       now,
	}
	ring := dropExpiredNotices(f.noticesByClient[ip], now)
	if !noticeIsMalicious(category) {
		replaced := false
		for i := range ring {
			if ring[i].category == category {
				ring[i] = item
				replaced = true
				break
			}
		}
		if !replaced {
			ring = append(ring, item)
		}
	} else {
		ring = append(ring, item)
	}
	if len(ring) > noticeRingCap {
		ring = ring[len(ring)-noticeRingCap:]
	}
	f.noticesByClient[ip] = ring
}

func (f *Forwarder) pruneDedupLocked(ip string, now time.Time) {
	prefix := ip + "|"
	kept := 0
	for key, at := range f.noticeDedup {
		if !strings.HasPrefix(key, prefix) {
			continue
		}
		if now.Sub(at) >= noticeDedupWindow {
			delete(f.noticeDedup, key)
			continue
		}
		kept++
	}
	if kept <= noticeDedupCap {
		return
	}
	// Drop the oldest keys for this IP until the cap holds.
	for kept > noticeDedupCap {
		var oldestKey string
		var oldest time.Time
		first := true
		for key, at := range f.noticeDedup {
			if !strings.HasPrefix(key, prefix) {
				continue
			}
			if first || at.Before(oldest) {
				oldestKey = key
				oldest = at
				first = false
			}
		}
		if oldestKey == "" {
			return
		}
		delete(f.noticeDedup, oldestKey)
		kept--
	}
}

func dropExpiredNotices(ring []dnsNotice, now time.Time) []dnsNotice {
	if len(ring) == 0 {
		return ring
	}
	out := ring[:0]
	for _, item := range ring {
		if now.Sub(item.at) <= noticeMaxAge {
			out = append(out, item)
		}
	}
	return out
}

// BlockedCategoryCounts returns absolute per-tunnel-IP category totals.
// No domain names are included.
func (f *Forwarder) BlockedCategoryCounts() map[string]map[string]uint64 {
	if f == nil {
		return nil
	}
	f.blockedMu.Lock()
	defer f.blockedMu.Unlock()
	out := make(map[string]map[string]uint64, len(f.blockedByCategory))
	for ip, cats := range f.blockedByCategory {
		copied := make(map[string]uint64, len(cats))
		for cat, n := range cats {
			copied[cat] = n
		}
		out[ip] = copied
	}
	return out
}

// NoticeSnapshot returns unexpired blocked-name notices still held in memory.
func (f *Forwarder) NoticeSnapshot(now time.Time) map[string][]DNSNotice {
	if f == nil {
		return nil
	}
	f.blockedMu.Lock()
	defer f.blockedMu.Unlock()
	out := make(map[string][]DNSNotice)
	for ip, ring := range f.noticesByClient {
		fresh := dropExpiredNotices(ring, now)
		f.noticesByClient[ip] = fresh
		if len(fresh) == 0 {
			delete(f.noticesByClient, ip)
			continue
		}
		copied := make([]DNSNotice, len(fresh))
		for i, item := range fresh {
			copied[i] = DNSNotice{
				ID:       item.id,
				Domain:   item.domain,
				Category: item.category,
				At:       item.at.Unix(),
			}
		}
		out[ip] = copied
	}
	return out
}

func (f *Forwarder) pruneNotices(now time.Time) {
	if f == nil {
		return
	}
	f.blockedMu.Lock()
	defer f.blockedMu.Unlock()
	for ip, ring := range f.noticesByClient {
		fresh := dropExpiredNotices(ring, now)
		if len(fresh) == 0 {
			delete(f.noticesByClient, ip)
			continue
		}
		f.noticesByClient[ip] = fresh
	}
	for key, at := range f.noticeDedup {
		if now.Sub(at) >= noticeDedupWindow {
			delete(f.noticeDedup, key)
		}
	}
}
