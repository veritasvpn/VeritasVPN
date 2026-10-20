package repository

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/veritasvpn/services/wg-manager/internal/shieldstats"
)

type Redis struct {
	client *redis.Client
}

func NewRedis(addr string) (*Redis, error) {
	opts, err := redis.ParseURL(addr)
	if err != nil {
		return nil, fmt.Errorf("parse redis url: %w", err)
	}
	return &Redis{client: redis.NewClient(opts)}, nil
}

func (r *Redis) Client() *redis.Client {
	return r.client
}

// IsTokenBlacklisted reports whether an access-token hash was revoked (logout).
// Key format matches auth-svc / billing-svc: blacklist:{sha256hex}.
func (r *Redis) IsTokenBlacklisted(ctx context.Context, tokenHash string) (bool, error) {
	key := fmt.Sprintf("blacklist:%s", tokenHash)
	exists, err := r.client.Exists(ctx, key).Result()
	return exists > 0, err
}

func (r *Redis) AllocateIP(ctx context.Context, serverID, subnet string) (string, error) {
	bitmapKey := fmt.Sprintf("ip_pool:%s:bitmap", serverID)

	bit, err := r.client.BitPos(ctx, bitmapKey, 0).Result()
	if err != nil {
		return "", fmt.Errorf("bitpos: %w", err)
	}
	if bit < 0 || bit > 253 {
		// Empty bitmap: BitPos returns -1; start at host .2
		if bit < 0 {
			bit = 0
		} else {
			return "", fmt.Errorf("no available IPs in subnet %s", subnet)
		}
	}

	ip := int(bit) + 2
	if err := r.client.SetBit(ctx, bitmapKey, int64(bit), 1).Err(); err != nil {
		return "", fmt.Errorf("setbit: %w", err)
	}

	prefix := strings.TrimSuffix(subnet, ".0/24")
	return fmt.Sprintf("%s.%d/32", prefix, ip), nil
}

func (r *Redis) ReleaseIP(ctx context.Context, serverID, ip string) error {
	bitmapKey := fmt.Sprintf("ip_pool:%s:bitmap", serverID)
	parts := strings.Split(ip, ".")
	if len(parts) != 4 && !strings.Contains(ip, "/") {
		return fmt.Errorf("invalid ip format: %s", ip)
	}
	lastOctet := strings.TrimSuffix(strings.Split(ip, "/")[0], "")
	octets := strings.Split(lastOctet, ".")
	last, _ := fmt.Sscanf(octets[len(octets)-1], "%d", new(int))
	_ = last

	var octetInt int
	fmt.Sscanf(octets[len(octets)-1], "%d", &octetInt)
	return r.client.SetBit(ctx, bitmapKey, int64(octetInt-2), 0).Err()
}

func dnsBlockedKey(assignedIP string) string {
	ip := strings.TrimSpace(assignedIP)
	if i := strings.IndexByte(ip, '/'); i >= 0 {
		ip = ip[:i]
	}
	return fmt.Sprintf("wg:dns_blocked:%s", ip)
}

// dnsBlockedCountTTL expires orphaned counters if peer cleanup misses a key
// (e.g. crash between ReleaseIP and Clear). Heartbeats refresh active keys.
const dnsBlockedCountTTL = 2 * time.Hour

// SetDNSBlockedCounts stores absolute per-client blocked counts from an agent heartbeat.
func (r *Redis) SetDNSBlockedCounts(ctx context.Context, counts map[string]uint64) error {
	if len(counts) == 0 {
		return nil
	}
	pipe := r.client.Pipeline()
	for ip, n := range counts {
		if ip == "" {
			continue
		}
		pipe.Set(ctx, dnsBlockedKey(ip), n, dnsBlockedCountTTL)
	}
	_, err := pipe.Exec(ctx)
	return err
}

// GetDNSBlockedCount returns the absolute blocked count for an assigned tunnel IP.
func (r *Redis) GetDNSBlockedCount(ctx context.Context, assignedIP string) (uint64, error) {
	val, err := r.client.Get(ctx, dnsBlockedKey(assignedIP)).Uint64()
	if err == redis.Nil {
		return 0, nil
	}
	return val, err
}

// ClearDNSBlockedCount removes the blocked-query counter for a released tunnel IP
// so a later peer reusing the address does not inherit stale UI counts.
func (r *Redis) ClearDNSBlockedCount(ctx context.Context, assignedIP string) error {
	if assignedIP == "" {
		return nil
	}
	return r.client.Del(ctx, dnsBlockedKey(assignedIP)).Err()
}

func dnsCategoryAbsKey(assignedIP string) string {
	return "wg:dns_cat_abs:" + tunnelIP(assignedIP)
}

func dnsNoticeKey(assignedIP string) string {
	return "wg:dns_notice:" + tunnelIP(assignedIP)
}

func dnsAccountDayKey(accountID, day string) string {
	return "wg:dns_acct:" + strings.TrimSpace(accountID) + ":" + day
}

func tunnelIP(assignedIP string) string {
	ip := strings.TrimSpace(assignedIP)
	if i := strings.IndexByte(ip, '/'); i >= 0 {
		ip = ip[:i]
	}
	return ip
}

// GetDNSCategoryAbs returns the last absolute per-category snapshot for a tunnel IP.
// The boolean is false when the key is missing.
func (r *Redis) GetDNSCategoryAbs(ctx context.Context, assignedIP string) (map[string]uint64, bool, error) {
	vals, err := r.client.HGetAll(ctx, dnsCategoryAbsKey(assignedIP)).Result()
	if err != nil {
		return nil, false, err
	}
	if len(vals) == 0 {
		return nil, false, nil
	}
	out := make(map[string]uint64, len(vals))
	for cat, raw := range vals {
		var n uint64
		if _, err := fmt.Sscan(raw, &n); err != nil {
			continue
		}
		out[cat] = n
	}
	return out, true, nil
}

// SetDNSCategoryAbs stores the absolute snapshot. TTL matches the session counter.
func (r *Redis) SetDNSCategoryAbs(ctx context.Context, assignedIP string, counts map[string]uint64) error {
	counts = shieldstats.Normalize(counts)
	key := dnsCategoryAbsKey(assignedIP)
	if len(counts) == 0 {
		return r.client.Del(ctx, key).Err()
	}
	pipe := r.client.TxPipeline()
	pipe.Del(ctx, key)
	fields := make([]interface{}, 0, len(counts)*2)
	for cat, n := range counts {
		fields = append(fields, cat, n)
	}
	pipe.HSet(ctx, key, fields...)
	pipe.Expire(ctx, key, shieldstats.AbsTTL)
	_, err := pipe.Exec(ctx)
	return err
}

// AddAccountCategoryCounts increments the UTC day bucket for an account. No names.
func (r *Redis) AddAccountCategoryCounts(ctx context.Context, accountID, day string, delta map[string]uint64) error {
	delta = shieldstats.Normalize(delta)
	if accountID == "" || day == "" || len(delta) == 0 {
		return nil
	}
	key := dnsAccountDayKey(accountID, day)
	pipe := r.client.TxPipeline()
	for cat, n := range delta {
		pipe.HIncrBy(ctx, key, cat, int64(n))
	}
	pipe.Expire(ctx, key, shieldstats.DayTTL)
	_, err := pipe.Exec(ctx)
	return err
}

// AccountCategoryCounts reads one UTC day. Missing keys are an empty map.
func (r *Redis) AccountCategoryCounts(ctx context.Context, accountID, day string) (map[string]uint64, error) {
	vals, err := r.client.HGetAll(ctx, dnsAccountDayKey(accountID, day)).Result()
	if err != nil {
		return nil, err
	}
	out := make(map[string]uint64, len(vals))
	for cat, raw := range vals {
		var n uint64
		if _, err := fmt.Sscan(raw, &n); err != nil {
			continue
		}
		out[cat] = n
	}
	return out, nil
}

// DeleteAccountShieldDays removes the recent day buckets for a deleted account.
func (r *Redis) DeleteAccountShieldDays(ctx context.Context, accountID string, now time.Time) error {
	if strings.TrimSpace(accountID) == "" {
		return nil
	}
	days := shieldstats.UTCDays(now, 10)
	keys := make([]string, len(days))
	for i, day := range days {
		keys[i] = dnsAccountDayKey(accountID, day)
	}
	return r.client.Del(ctx, keys...).Err()
}

// SetDNSNotices replaces the short-lived blocked-name buffer for a tunnel IP.
func (r *Redis) SetDNSNotices(ctx context.Context, assignedIP string, notices []shieldstats.Notice) error {
	key := dnsNoticeKey(assignedIP)
	if len(notices) == 0 {
		return r.client.Del(ctx, key).Err()
	}
	body, err := json.Marshal(notices)
	if err != nil {
		return err
	}
	return r.client.Set(ctx, key, body, shieldstats.NoticeTTL).Err()
}

// GetDNSNotices reads the buffer. A missing key is an empty list.
func (r *Redis) GetDNSNotices(ctx context.Context, assignedIP string) ([]shieldstats.Notice, error) {
	raw, err := r.client.Get(ctx, dnsNoticeKey(assignedIP)).Bytes()
	if err == redis.Nil {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var notices []shieldstats.Notice
	if err := json.Unmarshal(raw, &notices); err != nil {
		return nil, nil
	}
	return notices, nil
}

// ClearDNSShieldEphemeral removes tunnel-IP counter snapshots and the name buffer.
func (r *Redis) ClearDNSShieldEphemeral(ctx context.Context, assignedIP string) error {
	if tunnelIP(assignedIP) == "" {
		return nil
	}
	return r.client.Del(ctx,
		dnsBlockedKey(assignedIP),
		dnsCategoryAbsKey(assignedIP),
		dnsNoticeKey(assignedIP),
	).Err()
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
