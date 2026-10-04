package service

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"github.com/veritasvpn/lib/config"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestToolSignatures(t *testing.T) {
	secret := strings.Repeat("s", 64)
	svc := &AuthService{cfg: &config.Config{ToolsRateLimitSecret: secret}}
	sign := func(bucket, ip, ts string) string {
		m := hmac.New(sha256.New, []byte(secret))
		m.Write([]byte(ts + "\n" + bucket + "\n" + ip))
		return hex.EncodeToString(m.Sum(nil))
	}
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	sig := sign("check-ip", "192.0.2.1", ts)
	if !svc.VerifyToolRequest("check-ip", "192.0.2.1", ts, sig) {
		t.Fatal("valid signature rejected")
	}
	for _, v := range [][4]string{{"check-ip", "192.0.2.2", ts, sig}, {"check-breach", "192.0.2.1", ts, sig}, {"arbitrary", "192.0.2.1", ts, sig}, {"check-ip", "invalid", ts, sig}, {"check-ip", "192.0.2.1", "0", sign("check-ip", "192.0.2.1", "0")}} {
		if svc.VerifyToolRequest(v[0], v[1], v[2], v[3]) {
			t.Fatal("forged/expired request accepted")
		}
	}
	svc.cfg.ToolsRateLimitSecret = ""
	if svc.VerifyToolRequest("check-ip", "192.0.2.1", ts, sig) {
		t.Fatal("missing secret accepted")
	}
}
