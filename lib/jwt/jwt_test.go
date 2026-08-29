package jwt

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"testing"
	"time"
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

func TestEd25519AccessTokenAndVerifyOnlyManager(t *testing.T) {
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
	privatePEM := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: privateDER}))
	publicPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: publicDER}))
	publicJSON, err := json.Marshal(map[string]string{"2026-08": publicPEM})
	if err != nil {
		t.Fatal(err)
	}

	signer, err := NewManagerWithKeys("legacy-secret-at-least-32-characters", privatePEM, string(publicJSON), "2026-08", DefaultIssuer, DefaultAudience, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	token, _, err := signer.GenerateAccessToken("account", "premium")
	if err != nil {
		t.Fatal(err)
	}
	verifier, err := NewManagerWithKeys("", "", string(publicJSON), "", DefaultIssuer, DefaultAudience, time.Minute)
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
