package main

import (
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"testing"
	"time"
)

func hs256Token(t *testing.T, secret []byte, exp int64, sub, tier string) string {
	t.Helper()
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
	payloadBytes, _ := json.Marshal(claims{Exp: exp, Sub: sub, Tier: tier, Iss: "veritasvpn"})
	payload := base64.RawURLEncoding.EncodeToString(payloadBytes)
	input := header + "." + payload
	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write([]byte(input))
	return input + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

func ed25519Token(t *testing.T, private ed25519.PrivateKey, kid string, exp int64, sub, tier, iss, aud, tokenUse string) string {
	t.Helper()
	headerBytes, _ := json.Marshal(map[string]string{"alg": "EdDSA", "typ": "JWT", "kid": kid})
	header := base64.RawURLEncoding.EncodeToString(headerBytes)
	audRaw, _ := json.Marshal(aud)
	payloadBytes, _ := json.Marshal(claims{
		Exp:      exp,
		Sub:      sub,
		Tier:     tier,
		Iss:      iss,
		Aud:      audRaw,
		TokenUse: tokenUse,
	})
	payload := base64.RawURLEncoding.EncodeToString(payloadBytes)
	input := header + "." + payload
	sig := ed25519.Sign(private, []byte(input))
	return input + "." + base64.RawURLEncoding.EncodeToString(sig)
}

func TestValidateJWT(t *testing.T) {
	secret := []byte("a-production-length-test-secret-value")
	if !validateJWT(hs256Token(t, secret, time.Now().Add(time.Minute).Unix(), "account-1", "premium"), secret, nil, "https://api.veritasvpn.cloud", "veritasvpn-api") {
		t.Fatal("valid HS256 token rejected")
	}
	if validateJWT(hs256Token(t, secret, time.Now().Add(time.Minute).Unix(), "account-1", "free"), secret, nil, "https://api.veritasvpn.cloud", "veritasvpn-api") {
		t.Fatal("unpaid token accepted")
	}
	if validateJWT(hs256Token(t, secret, time.Now().Add(-time.Minute).Unix(), "account-1", "premium"), secret, nil, "https://api.veritasvpn.cloud", "veritasvpn-api") {
		t.Fatal("expired token accepted")
	}
	if validateJWT(hs256Token(t, secret, time.Now().Add(time.Minute).Unix(), "", "premium"), secret, nil, "https://api.veritasvpn.cloud", "veritasvpn-api") {
		t.Fatal("token without subject accepted")
	}
	if validateJWT(hs256Token(t, []byte("different-production-length-secret"), time.Now().Add(time.Minute).Unix(), "account-1", "premium"), secret, nil, "https://api.veritasvpn.cloud", "veritasvpn-api") {
		t.Fatal("invalid signature accepted")
	}
}

func TestValidateJWTEdDSAAndRejectHS256WithoutSecret(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	keys := map[string]ed25519.PublicKey{"kid-1": pub}
	issuer := "https://api.veritasvpn.cloud"
	audience := "veritasvpn-api"
	token := ed25519Token(t, priv, "kid-1", time.Now().Add(time.Minute).Unix(), "account-1", "premium", issuer, audience, "access")
	if !validateJWT(token, nil, keys, issuer, audience) {
		t.Fatal("valid EdDSA token rejected")
	}
	legacySecret := []byte("a-production-length-test-secret-value")
	if validateJWT(hs256Token(t, legacySecret, time.Now().Add(time.Minute).Unix(), "account-1", "premium"), nil, keys, issuer, audience) {
		t.Fatal("HS256 token must be rejected when secret is empty")
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
