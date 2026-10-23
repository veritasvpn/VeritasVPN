//go:build !race

package dns

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// Production list sizes on 2026-10-23, after first-category-wins. Raw feeds
// are larger than the merged categories: both malware mirrors are the full
// HaGeZi TIF mini list, phishing/scam/trackers overlap that list, and the two
// adult feeds overlap each other down to about 1.09M names. The merged policy
// is about 1.52M names. Keeping a map per raw feed next to the live policy
// and the replacement exceeded the 512Mi limit; this test fails if that
// shape comes back.
const (
	memMalwareN        = 213814
	memPhishingRawN    = 155498
	memPhishingUniqueN = 82803
	memScamRawN        = 125444
	memScamUniqueN     = 90000
	memTrackerRawN     = 58847
	memTrackerOverlapN = 22381
	memCryptoN         = 323
	memAdsN            = 3577
	memAdultN          = 1090000
	memNsfwN           = 476636
	memPornN           = 953406
)

func TestRefreshPeakMemoryWithProductionSizedFeeds(t *testing.T) {
	if testing.Short() {
		t.Skip("production-sized blocklist refresh")
	}

	dir := t.TempDir()
	malware := filepath.Join(dir, "malware.txt")
	if err := writeIndexedDomains(malware, "mal", 0, memMalwareN); err != nil {
		t.Fatal(err)
	}
	phishingOverlap := memPhishingRawN - memPhishingUniqueN
	phishing := filepath.Join(dir, "phishing.txt")
	if err := writeIndexedDomains(phishing, "mal", 0, phishingOverlap); err != nil {
		t.Fatal(err)
	}
	if err := appendIndexedDomains(phishing, "phi", 0, memPhishingUniqueN); err != nil {
		t.Fatal(err)
	}
	scamOverlap := memScamRawN - memScamUniqueN
	scam := filepath.Join(dir, "scam.txt")
	if err := writeIndexedDomains(scam, "mal", 0, scamOverlap); err != nil {
		t.Fatal(err)
	}
	if err := appendIndexedDomains(scam, "scm", 0, memScamUniqueN); err != nil {
		t.Fatal(err)
	}
	trackers := filepath.Join(dir, "trackers.txt")
	if err := writeIndexedDomains(trackers, "mal", 0, memTrackerOverlapN); err != nil {
		t.Fatal(err)
	}
	if err := appendIndexedDomains(trackers, "trk", 0, memTrackerRawN-memTrackerOverlapN); err != nil {
		t.Fatal(err)
	}
	crypto := filepath.Join(dir, "crypto.txt")
	if err := writeIndexedDomains(crypto, "cry", 0, memCryptoN); err != nil {
		t.Fatal(err)
	}
	ads := filepath.Join(dir, "ads.txt")
	if err := writeIndexedDomains(ads, "ads", 0, memAdsN); err != nil {
		t.Fatal(err)
	}
	nsfw := filepath.Join(dir, "nsfw.txt")
	if err := writeIndexedDomains(nsfw, "adu", 0, memNsfwN); err != nil {
		t.Fatal(err)
	}
	porn := filepath.Join(dir, "porn.txt")
	pornStart := memAdultN - memPornN
	if err := writeIndexedDomains(porn, "adu", pornStart, memPornN); err != nil {
		t.Fatal(err)
	}

	files := map[string]string{
		"/mal-a":    malware,
		"/mal-b":    malware,
		"/phish":    phishing,
		"/scam":     scam,
		"/crypto":   crypto,
		"/trackers": trackers,
		"/ads":      ads,
		"/nsfw":     nsfw,
		"/porn":     porn,
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f, err := os.Open(files[r.URL.Path])
		if err != nil {
			http.Error(w, "missing feed", http.StatusNotFound)
			return
		}
		defer f.Close()
		if _, err := io.Copy(w, f); err != nil {
			return
		}
	}))
	t.Cleanup(srv.Close)

	obs := &healthObserver{}
	state := filepath.Join(dir, "blocklist.txt")
	b := NewShieldBlocklist([]string{
		CategoryMalware,
		CategoryPhishing,
		CategoryScam,
		CategoryCrypto,
		CategoryTrackers,
		CategoryAds,
		CategoryAdult,
	}, map[string][]string{
		CategoryMalware:  {srv.URL + "/mal-a", srv.URL + "/mal-b"},
		CategoryPhishing: {srv.URL + "/phish"},
		CategoryScam:     {srv.URL + "/scam"},
		CategoryCrypto:   {srv.URL + "/crypto"},
		CategoryTrackers: {srv.URL + "/trackers"},
		CategoryAds:      {srv.URL + "/ads"},
		CategoryAdult:    {srv.URL + "/nsfw", srv.URL + "/porn"},
	}, state, time.Hour, obs, testLogger(t))
	client := srv.Client()
	client.Timeout = 3 * time.Minute
	b.client = client

	// First refresh builds the policy the next one has to keep serving.
	if err := b.refreshChecked(t.Context()); err != nil {
		t.Fatal(err)
	}
	runtime.GC()
	debug.FreeOSMemory()
	t.Logf("before second refresh %s", formatMem(rssBytes()))

	// The cgroup limit is resident memory, not Go's virtual Sys. 450Mi
	// HeapInuse / 460Mi RSS is above a two-map refresh (measured around
	// 381Mi inuse) and below the per-feed-map regression (about 478Mi
	// alloc / 543Mi Sys) that OOM-killed the 512Mi pod.
	const (
		maxPeakInuse = 450 << 20
		maxPeakRSS   = 460 << 20
		maxSteady    = 280 << 20
		minPeakInuse = 250 << 20
	)
	var peakInuse atomic.Uint64
	var peakRSS atomic.Uint64
	stop := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		statm, err := os.Open("/proc/self/statm")
		if err != nil {
			return
		}
		defer statm.Close()
		buf := make([]byte, 64)
		ticker := time.NewTicker(20 * time.Millisecond)
		defer ticker.Stop()
		sample := func() {
			var m runtime.MemStats
			runtime.ReadMemStats(&m)
			storeMax(&peakInuse, m.HeapInuse)
			storeMax(&peakRSS, readRSS(statm, buf))
		}
		for {
			select {
			case <-stop:
				sample()
				return
			case <-ticker.C:
				sample()
			}
		}
	}()
	if err := b.refreshChecked(context.Background()); err != nil {
		close(stop)
		<-done
		t.Fatal(err)
	}
	close(stop)
	<-done

	peakI := peakInuse.Load()
	peakR := peakRSS.Load()
	t.Logf("second refresh peak HeapInuse=%.1fMi RSS=%.1fMi", float64(peakI)/1024/1024, float64(peakR)/1024/1024)
	if peakR == 0 {
		t.Fatal("did not sample process RSS")
	}
	if peakI < minPeakInuse {
		t.Fatalf("peak HeapInuse %.1fMi is too small to have loaded the production-sized lists", float64(peakI)/1024/1024)
	}
	if peakI > maxPeakInuse {
		t.Fatalf("peak HeapInuse %.1fMi exceeds %dMi; per-feed maps would not fit in the 512Mi pod", float64(peakI)/1024/1024, maxPeakInuse>>20)
	}
	if peakR > maxPeakRSS {
		t.Fatalf("peak RSS %.1fMi exceeds %dMi; the 512Mi pod would have no headroom", float64(peakR)/1024/1024, maxPeakRSS>>20)
	}

	runtime.GC()
	debug.FreeOSMemory()
	var steady runtime.MemStats
	runtime.ReadMemStats(&steady)
	steadyRSS := rssBytes()
	t.Logf("steady HeapInuse=%.1fMi RSS=%.1fMi released=%.1fMi domains=%d",
		float64(steady.HeapInuse)/1024/1024, float64(steadyRSS)/1024/1024, float64(steady.HeapReleased)/1024/1024, b.domainCount())
	if steady.HeapInuse > maxSteady {
		t.Fatalf("steady HeapInuse %.1fMi exceeds %dMi; the replaced policy was not released", float64(steady.HeapInuse)/1024/1024, maxSteady>>20)
	}
	if steadyRSS > maxSteady {
		t.Fatalf("steady RSS %.1fMi exceeds %dMi; idle heap was not returned to the OS", float64(steadyRSS)/1024/1024, maxSteady>>20)
	}

	want := memMalwareN + memPhishingUniqueN + memScamUniqueN + memCryptoN + (memTrackerRawN - memTrackerOverlapN) + memAdsN + memAdultN + 2 + len(builtInDoHBypassDomains)
	if got := b.domainCount(); got != want {
		t.Fatalf("merged domains=%d want %d", got, want)
	}
	assertCategory := func(name, category string) {
		t.Helper()
		got, ok := b.BlockedCategory(name)
		if !ok || got != category {
			t.Fatalf("%s category=%q ok=%v want %s", name, got, ok, category)
		}
	}
	assertCategory(indexedDomain("mal", 1), CategoryMalware)
	assertCategory(indexedDomain("mal", 0), CategoryMalware) // also listed on the phishing feed
	assertCategory(indexedDomain("phi", 1), CategoryPhishing)
	assertCategory(indexedDomain("scm", 1), CategoryScam)
	assertCategory(indexedDomain("trk", 1), CategoryTrackers)
	assertCategory(indexedDomain("adu", 1), CategoryAdult)
	assertCategory(indexedDomain("adu", memAdultN-1), CategoryAdult)
	for _, cat := range []string{CategoryMalware, CategoryPhishing, CategoryScam, CategoryCrypto, CategoryTrackers, CategoryAds, CategoryAdult} {
		if obs.degraded[cat] {
			t.Fatalf("category %s degraded on a fully successful refresh", cat)
		}
	}
	if _, err := os.Stat(b.feedCachePath(srv.URL + "/mal-a")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(b.feedCachePath(srv.URL + "/mal-b")); err != nil {
		t.Fatal(err)
	}
}

func (b *Blocklist) domainCount() int {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return len(b.domains)
}

func formatMem(rss uint64) string {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return fmt.Sprintf("HeapInuse=%.1fMi RSS=%.1fMi released=%.1fMi",
		float64(m.HeapInuse)/1024/1024, float64(rss)/1024/1024, float64(m.HeapReleased)/1024/1024)
}

func storeMax(dst *atomic.Uint64, v uint64) {
	for {
		old := dst.Load()
		if v <= old || dst.CompareAndSwap(old, v) {
			return
		}
	}
}

func rssBytes() uint64 {
	f, err := os.Open("/proc/self/statm")
	if err != nil {
		return 0
	}
	defer f.Close()
	return readRSS(f, make([]byte, 64))
}

func readRSS(f *os.File, buf []byte) uint64 {
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return 0
	}
	n, err := f.Read(buf)
	if n == 0 || (err != nil && err != io.EOF) {
		return 0
	}
	fields := strings.Fields(string(buf[:n]))
	if len(fields) < 2 {
		return 0
	}
	pages, err := strconv.ParseUint(fields[1], 10, 64)
	if err != nil {
		return 0
	}
	return pages * uint64(os.Getpagesize())
}

// refreshChecked fails the refresh when the parent context expires. A timed-out
// download looks like a feed failure and would otherwise shrink the policy.
func (b *Blocklist) refreshChecked(ctx context.Context) error {
	b.refresh(ctx)
	if err := ctx.Err(); err != nil {
		return fmt.Errorf("blocklist refresh context: %w", err)
	}
	return nil
}

func writeIndexedDomains(path, suffix string, start, n int) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	w := bufio.NewWriterSize(f, 1<<20)
	if err := writeIndexed(w, suffix, start, n); err != nil {
		f.Close()
		return err
	}
	if err := w.Flush(); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

func appendIndexedDomains(path, suffix string, start, n int) error {
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	w := bufio.NewWriterSize(f, 1<<20)
	if err := writeIndexed(w, suffix, start, n); err != nil {
		f.Close()
		return err
	}
	if err := w.Flush(); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

func writeIndexed(w *bufio.Writer, suffix string, start, n int) error {
	for i := start; i < start+n; i++ {
		if _, err := w.WriteString(indexedDomain(suffix, i)); err != nil {
			return err
		}
		if err := w.WriteByte('\n'); err != nil {
			return err
		}
	}
	return nil
}

func indexedDomain(suffix string, i int) string {
	var digits [7]byte
	for d := 6; d >= 0; d-- {
		digits[d] = byte('0' + i%10)
		i /= 10
	}
	return suffix + "-" + string(digits[:]) + ".example-block.test"
}
