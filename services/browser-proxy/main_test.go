package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"testing"
	"time"
)

func token(t *testing.T, secret []byte, exp int64, sub, tier string) string {
	t.Helper()
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	payloadBytes, _ := json.Marshal(claims{Exp: exp, Sub: sub, Tier: tier, Iss: "veritasvpn"})
	payload := base64.RawURLEncoding.EncodeToString(payloadBytes)
	input := header + "." + payload
	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write([]byte(input))
	return input + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

func TestValidateJWT(t *testing.T) {
	secret := []byte("a-production-length-test-secret-value")
	if !validateJWT(token(t, secret, time.Now().Add(time.Minute).Unix(), "account-1", "premium"), secret) {
		t.Fatal("valid token rejected")
	}
	if validateJWT(token(t, secret, time.Now().Add(time.Minute).Unix(), "account-1", "free"), secret) {
		t.Fatal("unpaid token accepted")
	}
	if validateJWT(token(t, secret, time.Now().Add(-time.Minute).Unix(), "account-1", "premium"), secret) {
		t.Fatal("expired token accepted")
	}
	if validateJWT(token(t, secret, time.Now().Add(time.Minute).Unix(), "", "premium"), secret) {
		t.Fatal("token without subject accepted")
	}
	if validateJWT(token(t, []byte("different-production-length-secret"), time.Now().Add(time.Minute).Unix(), "account-1", "premium"), secret) {
		t.Fatal("invalid signature accepted")
	}
}

func TestAllowedTargetPolicy(t *testing.T) {
	if _, err := allowedTarget("localhost:443"); err == nil {
		t.Fatal("localhost target accepted")
	}
	if _, err := allowedTarget("example.com:22"); err == nil {
		t.Fatal("non-web port accepted")
	}
	if _, err := allowedTarget("example.com:443"); err != nil {
		t.Fatalf("public HTTPS target rejected: %v", err)
	}
}
