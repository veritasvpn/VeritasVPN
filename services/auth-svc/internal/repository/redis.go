package repository

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
)

type Redis struct {
	client *redis.Client
}

func NewRedis(addr string) (*Redis, error) {
	opts, err := redis.ParseURL(addr)
	if err != nil {
		return nil, fmt.Errorf("parse redis url: %w", err)
	}

	client := redis.NewClient(opts)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := client.Ping(ctx).Err(); err != nil {
		return nil, fmt.Errorf("redis ping: %w", err)
	}

	return &Redis{client: client}, nil
}

func (r *Redis) SetSession(ctx context.Context, tokenHash, accountID string, ttl time.Duration) error {
	key := fmt.Sprintf("session:%s", tokenHash)
	return r.client.Set(ctx, key, accountID, ttl).Err()
}

func (r *Redis) GetSession(ctx context.Context, tokenHash string) (string, error) {
	key := fmt.Sprintf("session:%s", tokenHash)
	return r.client.Get(ctx, key).Result()
}

func (r *Redis) DeleteSession(ctx context.Context, tokenHash string) error {
	key := fmt.Sprintf("session:%s", tokenHash)
	return r.client.Del(ctx, key).Err()
}

func (r *Redis) CheckRateLimit(ctx context.Context, key string, limit int, window time.Duration) (bool, error) {
	count, err := r.IncrementRateLimit(ctx, key, window)
	if err != nil {
		return false, err
	}
	return count > int64(limit), nil
}

// IncrementRateLimit records an attempt and returns the current count. Handlers
// use the count when a low-friction action becomes higher risk before its hard
// rate limit is reached.
func (r *Redis) IncrementRateLimit(ctx context.Context, key string, window time.Duration) (int64, error) {
	pipe := r.client.Pipeline()
	incr := pipe.Incr(ctx, key)
	pipe.Expire(ctx, key, window)

	if _, err := pipe.Exec(ctx); err != nil {
		return 0, fmt.Errorf("rate limit check: %w", err)
	}
	return incr.Val(), nil
}

func (r *Redis) ClearRateLimit(ctx context.Context, key string) error {
	return r.client.Del(ctx, key).Err()
}

func (r *Redis) BlacklistToken(ctx context.Context, tokenHash string, ttl time.Duration) error {
	key := fmt.Sprintf("blacklist:%s", tokenHash)
	return r.client.Set(ctx, key, "1", ttl).Err()
}

func (r *Redis) IsTokenBlacklisted(ctx context.Context, tokenHash string) (bool, error) {
	key := fmt.Sprintf("blacklist:%s", tokenHash)
	exists, err := r.client.Exists(ctx, key).Result()
	return exists > 0, err
}

// GetAccountSessionVersion returns the account-wide JWT revocation generation.
// Missing keys are generation zero for accounts created before this control.
func (r *Redis) GetAccountSessionVersion(ctx context.Context, accountID string) (int64, error) {
	key := fmt.Sprintf("account-session-version:%s", accountID)
	value, err := r.client.Get(ctx, key).Int64()
	if err == redis.Nil {
		return 0, nil
	}
	return value, err
}

// IncrementAccountSessionVersion invalidates every access JWT already issued
// for the account. The TTL exceeds both refresh and access token lifetimes.
func (r *Redis) IncrementAccountSessionVersion(ctx context.Context, accountID string, ttl time.Duration) error {
	key := fmt.Sprintf("account-session-version:%s", accountID)
	pipe := r.client.TxPipeline()
	pipe.Incr(ctx, key)
	pipe.Expire(ctx, key, ttl)
	_, err := pipe.Exec(ctx)
	return err
}
