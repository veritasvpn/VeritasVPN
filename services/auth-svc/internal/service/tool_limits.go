package service

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"net/netip"
	"strconv"
	"time"
)

func toolQuota(bucket string) int {
	switch bucket {
	case "check-ip":
		return 60
	case "check-dns-session":
		return 20
	case "check-breach":
		return 10
	}
	return 0
}

func (s *AuthService) VerifyToolRequest(bucket, ip, timestamp, signature string) bool {
	if len(s.cfg.ToolsRateLimitSecret) < 32 || toolQuota(bucket) == 0 {
		return false
	}
	addr, err := netip.ParseAddr(ip)
	if err != nil || addr.Zone() != "" {
		return false
	}
	seconds, err := strconv.ParseInt(timestamp, 10, 64)
	now := time.Now().Unix()
	if err != nil || seconds < now-30 || seconds > now+30 {
		return false
	}
	sig, err := hex.DecodeString(signature)
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, []byte(s.cfg.ToolsRateLimitSecret))
	mac.Write([]byte(timestamp + "\n" + bucket + "\n" + ip))
	return hmac.Equal(mac.Sum(nil), sig)
}

func (s *AuthService) LimitToolRequest(ctx context.Context, bucket, ip string) (bool, error) {
	// Normalize before keying so IPv6 spelling/mapped forms share the same quota.
	addr, err := netip.ParseAddr(ip)
	if err != nil || toolQuota(bucket) == 0 {
		return true, err
	}
	return s.redis.CheckRateLimit(ctx, "tool:"+bucket+":"+hashInput(addr.Unmap().String()), toolQuota(bucket), time.Minute)
}
