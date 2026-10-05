package repository

import (
	"context"
	"errors"
	"fmt"
	"github.com/jackc/pgx/v5/pgxpool"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestResetTokenConcurrency(t *testing.T) {
	dsn := os.Getenv("SECURITY_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("requires disposable SECURITY_TEST_DATABASE_URL")
	}
	ctx := context.Background()
	admin, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	schema := fmt.Sprintf("security_test_%d", time.Now().UnixNano())
	if _, err = admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer admin.Exec(ctx, "DROP SCHEMA "+schema+" CASCADE")
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	_, err = pool.Exec(ctx, `CREATE TABLE accounts(id text PRIMARY KEY,password_hash text,email_verified_at timestamptz,
 verification_token_hash text,verification_token_expiry timestamptz,reset_token text,reset_token_expiry timestamptz,account_status text);
 CREATE TABLE refresh_tokens(account_id text);
 INSERT INTO accounts(id,password_hash,account_status) VALUES('test','old','active');`)
	if err != nil {
		t.Fatal(err)
	}
	db := NewPostgres(pool)
	var wg sync.WaitGroup
	var issued atomic.Int32
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			ok, e := db.SetResetToken(ctx, "test", fmt.Sprint(i), time.Now().Add(time.Minute))
			if e != nil {
				t.Error(e)
			}
			if ok {
				issued.Add(1)
			}
		}(i)
	}
	wg.Wait()
	if issued.Load() != 1 {
		t.Fatalf("issued %d tokens", issued.Load())
	}
	var token string
	pool.QueryRow(ctx, "SELECT reset_token FROM accounts WHERE id='test'").Scan(&token)
	pool.Exec(ctx, "INSERT INTO refresh_tokens VALUES('test')")
	// Failed access-session revocation must leave the token/password intact.
	if db.CompletePasswordReset(ctx, token, "new", func(context.Context, string) error { return errors.New("redis unavailable") }) == nil {
		t.Fatal("revocation failure allowed reset")
	}
	var password string
	pool.QueryRow(ctx, "SELECT password_hash FROM accounts WHERE id='test'").Scan(&password)
	if password != "old" {
		t.Fatal("password changed despite rollback")
	}
	var won, revocations atomic.Int32
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			e := db.CompletePasswordReset(ctx, token, "new", func(context.Context, string) error { revocations.Add(1); return nil })
			if e == nil {
				won.Add(1)
			}
		}()
	}
	wg.Wait()
	if won.Load() != 1 || revocations.Load() != 1 {
		t.Fatalf("winners=%d revocations=%d", won.Load(), revocations.Load())
	}
	var count int
	pool.QueryRow(ctx, "SELECT count(*) FROM refresh_tokens").Scan(&count)
	if count != 0 {
		t.Fatal("refresh tokens remain")
	}
	if db.CompletePasswordReset(ctx, token, "replay", func(context.Context, string) error { return nil }) == nil {
		t.Fatal("token replay accepted")
	}
	ok, err := db.SetResetToken(ctx, "test", "expired", time.Now().Add(-time.Minute))
	if !ok || err != nil {
		t.Fatal("setup expired token", err)
	}
	if db.CompletePasswordReset(ctx, "expired", "bad", func(context.Context, string) error { return nil }) == nil {
		t.Fatal("expired token accepted")
	}
	db.SetResetToken(ctx, "test", "newest", time.Now().Add(time.Minute))
	db.ClearResetToken(ctx, "test", "expired")
	pool.QueryRow(ctx, "SELECT reset_token FROM accounts WHERE id='test'").Scan(&token)
	if token != "newest" {
		t.Fatal("stale delivery cleanup removed a newer token")
	}
}

func TestAtomicQuota(t *testing.T) {
	url := os.Getenv("SECURITY_TEST_REDIS_URL")
	if url == "" {
		t.Skip("requires disposable SECURITY_TEST_REDIS_URL")
	}
	db, err := NewRedis(url)
	if err != nil {
		t.Fatal(err)
	}
	defer db.client.Close()
	ctx := context.Background()
	key := fmt.Sprintf("security-test:%d", time.Now().UnixNano())
	defer db.ClearRateLimit(ctx, key)
	var wg sync.WaitGroup
	var allowed atomic.Int32
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			limited, e := db.CheckRateLimit(ctx, key, 10, time.Minute)
			if e != nil {
				t.Error(e)
			} else if !limited {
				allowed.Add(1)
			}
		}()
	}
	wg.Wait()
	if allowed.Load() != 10 {
		t.Fatalf("allowed %d, expected 10", allowed.Load())
	}
	ttl, err := db.client.TTL(ctx, key).Result()
	if err != nil || ttl <= 0 || ttl > time.Minute {
		t.Fatal("quota has no bounded TTL", ttl, err)
	}
}
