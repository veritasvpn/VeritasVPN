package repository

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/veritasvpn/services/wg-manager/internal/shieldstats"
)

func TestShieldRedisCountersAndNotices(t *testing.T) {
	url := os.Getenv("REDIS_URL")
	if url == "" {
		t.Skip("REDIS_URL is not set")
	}
	r, err := NewRedis(url)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	account := "acct-shield-test"
	ip := "10.66.66.66"
	t.Cleanup(func() {
		_ = r.ClearDNSShieldEphemeral(ctx, ip)
		_ = r.DeleteAccountShieldDays(ctx, account, time.Now().UTC())
	})

	if _, had, err := r.GetDNSCategoryAbs(ctx, ip); err != nil || had {
		t.Fatalf("missing snapshot had=%v err=%v", had, err)
	}
	if err := r.AddAccountCategoryCounts(ctx, account, time.Now().UTC().Format("2006-01-02"), map[string]uint64{"malware": 2, "nope": 9}); err != nil {
		t.Fatal(err)
	}
	got, err := r.AccountCategoryCounts(ctx, account, time.Now().UTC().Format("2006-01-02"))
	if err != nil {
		t.Fatal(err)
	}
	if got["malware"] != 2 || got["nope"] != 0 {
		t.Fatalf("day bucket=%v", got)
	}
	notices := []shieldstats.Notice{{
		ID: "0123456789abcdef", Domain: "evil.example", Category: "scam", At: time.Now().Unix(),
	}}
	if err := r.SetDNSNotices(ctx, ip+"/32", notices); err != nil {
		t.Fatal(err)
	}
	read, err := r.GetDNSNotices(ctx, ip)
	if err != nil || len(read) != 1 || read[0].Domain != "evil.example" {
		t.Fatalf("notices=%v err=%v", read, err)
	}
	if err := r.ClearDNSShieldEphemeral(ctx, ip); err != nil {
		t.Fatal(err)
	}
	read, err = r.GetDNSNotices(ctx, ip)
	if err != nil || len(read) != 0 {
		t.Fatalf("cleared notices=%v err=%v", read, err)
	}
	n, err := r.GetDNSBlockedCount(ctx, ip)
	if err != nil || n != 0 {
		t.Fatalf("count=%d err=%v", n, err)
	}
}
