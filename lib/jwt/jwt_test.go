package jwt

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"os"
	"testing"
	"time"

	jwtv5 "github.com/golang-jwt/jwt/v5"
)

func TestGenerateJTIIsUnique(t *testing.T) {
	a := generateJTI()
	b := generateJTI()
	if len(a) != 32 || len(b) != 32 {
		t.Fatalf("expected 32 hex chars, got %q and %q", a, b)
	}
	if a == b {
		t.Fatal("JTI must not be identical across calls")
	}
	if a == "00000000000000000000000000000000" {
		t.Fatal("JTI must not be all zeros")
	}
}

func ed25519Material(t *testing.T) (privatePEM, publicJSON string) {
	t.Helper()
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	privateDER, err := x509.MarshalPKCS8PrivateKey(privateKey)
	if err != nil {
		t.Fatal(err)
	}
	publicDER, err := x509.MarshalPKIXPublicKey(publicKey)
	if err != nil {
		t.Fatal(err)
	}
	privatePEM = string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: privateDER}))
	publicPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: publicDER}))
	publicJSONBytes, err := json.Marshal(map[string]string{"2026-08": publicPEM})
	if err != nil {
		t.Fatal(err)
	}
	return privatePEM, string(publicJSONBytes)
}

func TestEd25519AccessTokenAndVerifyOnlyManager(t *testing.T) {
	privatePEM, publicJSON := ed25519Material(t)

	signer, err := NewManagerWithKeys("", privatePEM, publicJSON, "2026-08", DefaultIssuer, DefaultAudience, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	token, _, err := signer.GenerateAccessToken("account", "premium")
	if err != nil {
		t.Fatal(err)
	}
	verifier, err := NewManagerWithKeys("", "", publicJSON, "", DefaultIssuer, DefaultAudience, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	claims, err := verifier.ValidateAccessToken(token)
	if err != nil {
		t.Fatal(err)
	}
	if claims.AccountID != "account" || claims.TokenUse != AccessTokenUse {
		t.Fatalf("unexpected claims: %#v", claims)
	}
	if _, _, err := verifier.GenerateAccessToken("account", "premium"); err == nil {
		t.Fatal("verify-only manager must not mint tokens")
	}
}

func TestRejectHS256WhenSecretEmpty(t *testing.T) {
	_, publicJSON := ed25519Material(t)
	verifier, err := NewManagerWithKeys("", "", publicJSON, "", DefaultIssuer, DefaultAudience, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	legacy := jwtv5.NewWithClaims(jwtv5.SigningMethodHS256, jwtv5.MapClaims{
		"account_id": "account",
		"sub":        "account",
		"iss":        "veritasvpn",
		"tier":       "premium",
		"exp":        time.Now().Add(time.Minute).Unix(),
	})
	token, err := legacy.SignedString([]byte("legacy-secret-at-least-32-characters"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := verifier.ValidateAccessToken(token); err == nil {
		t.Fatal("HS256 token must be rejected when JWT_SECRET is unset")
	}
}

func TestRejectUnknownKid(t *testing.T) {
	privatePEM, publicJSON := ed25519Material(t)
	signer, err := NewManagerWithKeys("", privatePEM, publicJSON, "2026-08", DefaultIssuer, DefaultAudience, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	token, _, err := signer.GenerateAccessToken("account", "premium")
	if err != nil {
		t.Fatal(err)
	}
	otherPub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	otherDER, err := x509.MarshalPKIXPublicKey(otherPub)
	if err != nil {
		t.Fatal(err)
	}
	otherPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: otherDER}))
	otherJSON, err := json.Marshal(map[string]string{"other": otherPEM})
	if err != nil {
		t.Fatal(err)
	}
	verifier, err := NewManagerWithKeys("", "", string(otherJSON), "", DefaultIssuer, DefaultAudience, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := verifier.ValidateAccessToken(token); err == nil {
		t.Fatal("token with unknown kid must be rejected")
	}
}

func TestProductionBlocksHS256Mint(t *testing.T) {
	t.Setenv("ENVIRONMENT", "production")
	defer os.Unsetenv("ENVIRONMENT")
	m := NewManager("legacy-secret-at-least-32-characters", time.Minute, time.Hour)
	if _, _, err := m.GenerateAccessToken("account", "premium"); err == nil {
		t.Fatal("expected HS256 mint to fail in production")
	}
}

func TestDualVerifyAcceptsLegacyHS256WhenSecretConfigured(t *testing.T) {
	secret := "legacy-secret-at-least-32-characters"
	_, publicJSON := ed25519Material(t)
	verifier, err := NewManagerWithKeys(secret, "", publicJSON, "", DefaultIssuer, DefaultAudience, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	legacy := jwtv5.NewWithClaims(jwtv5.SigningMethodHS256, jwtv5.MapClaims{
		"account_id": "account",
		"sub":        "account",
		"iss":        "veritasvpn",
		"tier":       "premium",
		"exp":        time.Now().Add(time.Minute).Unix(),
	})
	token, err := legacy.SignedString([]byte(secret))
	if err != nil {
		t.Fatal(err)
	}
	claims, err := verifier.ValidateAccessToken(token)
	if err != nil {
		t.Fatal(err)
	}
	if claims.AccountID != "account" {
		t.Fatalf("unexpected account: %q", claims.AccountID)
	}
}
