package metrics

import (
	"testing"

	"github.com/prometheus/client_golang/prometheus/testutil"
)

func TestBlocklistFeedHealthResetsDegradedGauge(t *testing.T) {
	m := New("0")
	m.DNSBlocklistFeedHealth(map[string]int{"malware": 2}, map[string]bool{
		"malware":  true,
		"phishing": false,
	})
	if got := testutil.ToFloat64(m.DNSBlocklistCategoryDegraded.WithLabelValues("malware")); got != 1 {
		t.Fatalf("malware degraded = %v", got)
	}
	if got := testutil.ToFloat64(m.DNSBlocklistCategoryDegraded.WithLabelValues("phishing")); got != 0 {
		t.Fatalf("phishing degraded = %v", got)
	}
	if got := testutil.ToFloat64(m.DNSBlocklistFeedFailures.WithLabelValues("malware")); got != 2 {
		t.Fatalf("feed failures = %v", got)
	}

	m.DNSBlocklistFeedHealth(nil, map[string]bool{
		"malware":  false,
		"phishing": false,
	})
	if got := testutil.ToFloat64(m.DNSBlocklistCategoryDegraded.WithLabelValues("malware")); got != 0 {
		t.Fatalf("malware degraded after recovery = %v", got)
	}
	if got := testutil.ToFloat64(m.DNSBlocklistFeedFailures.WithLabelValues("malware")); got != 2 {
		t.Fatalf("recovery must not rewind the failure counter, got %v", got)
	}
}
