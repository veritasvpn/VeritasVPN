package service

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/veritasvpn/lib/config"
)

func e2eSignature(secret, timestamp, accountID string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = fmt.Fprintf(mac, "%s\n%s", timestamp, accountID)
	return hex.EncodeToString(mac.Sum(nil))
}

func TestVerifyE2EAuth(t *testing.T) {
	secret := strings.Repeat("a", 64)
	accountID := "synthetic-account"
	svc := &AuthService{cfg: &config.Config{E2EAuthSecret: secret, E2EAuthAccountID: accountID}}
	timestamp := fmt.Sprint(time.Now().Unix())
	if !svc.VerifyE2EAuth(accountID, timestamp, e2eSignature(secret, timestamp, accountID)) {
		t.Fatal("valid E2E signature was rejected")
	}
	if svc.VerifyE2EAuth("other-account", timestamp, e2eSignature(secret, timestamp, "other-account")) {
		t.Fatal("signature was accepted for a non-synthetic account")
	}
	old := fmt.Sprint(time.Now().Add(-3 * time.Minute).Unix())
	if svc.VerifyE2EAuth(accountID, old, e2eSignature(secret, old, accountID)) {
		t.Fatal("expired E2E signature was accepted")
	}
}
