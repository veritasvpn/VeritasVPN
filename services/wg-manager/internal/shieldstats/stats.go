package shieldstats

import (
	"strings"
	"time"
)

// Categories are the DNS feed names. Clients roll malware+phishing+scam+crypto
// up into "malicious". Names are never stored in these counters.
var Categories = []string{
	"malware", "phishing", "scam", "crypto", "trackers", "ads", "adult",
}

const (
	// DayTTL keeps eight UTC days so a 7-day window survives until the next midnight.
	DayTTL = 8 * 24 * time.Hour
	// NoticeTTL is how long a blocked name stays in the Redis cache.
	NoticeTTL = 120 * time.Second
	// AbsTTL matches the existing tunnel-IP session counter.
	AbsTTL = 2 * time.Hour
)

// Notice is a blocked name waiting for the app to display. It is not a query log.
type Notice struct {
	ID       string `json:"id"`
	Domain   string `json:"domain"`
	Category string `json:"category"`
	At       int64  `json:"at"`
}

func known(category string) bool {
	switch category {
	case "malware", "phishing", "scam", "crypto", "trackers", "ads", "adult":
		return true
	default:
		return false
	}
}

// Normalize keeps known categories and drops everything else.
func Normalize(in map[string]uint64) map[string]uint64 {
	if len(in) == 0 {
		return nil
	}
	out := make(map[string]uint64, len(Categories))
	for cat, n := range in {
		cat = strings.ToLower(strings.TrimSpace(cat))
		if n == 0 || !known(cat) {
			continue
		}
		out[cat] += n
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// Delta is the positive increase from a previous absolute snapshot.
// hadPrevious is false the first time an IP is seen: the caller stores the
// snapshot and adds nothing, so a long-lived agent total is not dumped into
// "today" on deploy. reset is true when the agent restarted (a count went
// backwards). The caller stores the new snapshot and adds nothing.
func Delta(previous map[string]uint64, current map[string]uint64, hadPrevious bool) (delta map[string]uint64, reset bool) {
	current = Normalize(current)
	if !hadPrevious || len(current) == 0 {
		return nil, false
	}
	previous = Normalize(previous)
	for cat, n := range current {
		if n < previous[cat] {
			return nil, true
		}
	}
	out := make(map[string]uint64)
	for cat, n := range current {
		if d := n - previous[cat]; d > 0 {
			out[cat] = d
		}
	}
	if len(out) == 0 {
		return nil, false
	}
	return out, false
}

// EmptyWindow is a zero count for every category so clients can hide rows
// that are off without treating a missing key as "unknown".
func EmptyWindow() map[string]uint64 {
	out := make(map[string]uint64, len(Categories))
	for _, cat := range Categories {
		out[cat] = 0
	}
	return out
}

// AddWindows sums category maps. Missing keys are zero.
func AddWindows(dst, src map[string]uint64) map[string]uint64 {
	if dst == nil {
		dst = EmptyWindow()
	}
	for _, cat := range Categories {
		dst[cat] += src[cat]
	}
	return dst
}

// UTCDays returns n UTC dates ending at now, oldest first. n=7 is "this week".
func UTCDays(now time.Time, n int) []string {
	if n < 1 {
		return nil
	}
	day := now.UTC().Truncate(24 * time.Hour)
	out := make([]string, n)
	for i := n - 1; i >= 0; i-- {
		out[i] = day.Format("2006-01-02")
		day = day.AddDate(0, 0, -1)
	}
	return out
}

// SanitizeNotices keeps a short, well-formed list. peerCreated drops names
// from a tunnel IP that belonged to an earlier peer.
func SanitizeNotices(in []Notice, now time.Time, peerCreated time.Time) []Notice {
	if len(in) == 0 {
		return nil
	}
	cutoff := peerCreated.UTC().Unix()
	oldest := now.UTC().Add(-NoticeTTL).Unix()
	out := make([]Notice, 0, len(in))
	seen := make(map[string]struct{}, len(in))
	for _, n := range in {
		n.Domain = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(n.Domain)), ".")
		n.Category = strings.ToLower(strings.TrimSpace(n.Category))
		n.ID = strings.ToLower(strings.TrimSpace(n.ID))
		if !validDomain(n.Domain) || !known(n.Category) || n.At < oldest || n.At > now.UTC().Add(time.Minute).Unix() {
			continue
		}
		if !peerCreated.IsZero() && n.At < cutoff {
			continue
		}
		if len(n.ID) != 16 || strings.IndexFunc(n.ID, func(r rune) bool {
			return (r < '0' || r > '9') && (r < 'a' || r > 'f')
		}) >= 0 {
			continue
		}
		if _, ok := seen[n.ID]; ok {
			continue
		}
		seen[n.ID] = struct{}{}
		out = append(out, n)
		if len(out) == 8 {
			break
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func validDomain(domain string) bool {
	if len(domain) == 0 || len(domain) > 253 || !strings.Contains(domain, ".") || strings.Contains(domain, "/") || strings.Contains(domain, " ") {
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
