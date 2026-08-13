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
	pipe := r.client.Pipeline()
	incr := pipe.Incr(ctx, key)
	pipe.Expire(ctx, key, window)

	if _, err := pipe.Exec(ctx); err != nil {
		return false, fmt.Errorf("rate limit check: %w", err)
	}

	return incr.Val() > int64(limit), nil
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
