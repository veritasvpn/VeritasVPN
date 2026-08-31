package tokenauth

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"testing"
	"time"

	jwtlib "github.com/veritasvpn/lib/jwt"
)

type stubBlacklist struct {
	blacklisted bool
}

func (s stubBlacklist) IsTokenBlacklisted(_ context.Context, _ string) (bool, error) {
	return s.blacklisted, nil
}

func ed25519VerifierMaterial(t *testing.T) (token string, publicJSON string) {
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
	privatePEM := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: privateDER}))
	publicPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: publicDER}))
	publicJSONBytes, err := json.Marshal(map[string]string{"test-kid": publicPEM})
	if err != nil {
		t.Fatal(err)
	}
	publicJSON = string(publicJSONBytes)
	signer, err := jwtlib.NewManagerWithKeys("", privatePEM, publicJSON, "test-kid", jwtlib.DefaultIssuer, jwtlib.DefaultAudience, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	token, _, err = signer.GenerateAccessToken("acc-test", "free")
	if err != nil {
		t.Fatal(err)
	}
	return token, publicJSON
}

func TestVerifierRejectsBlacklistedToken(t *testing.T) {
	token, publicJSON := ed25519VerifierMaterial(t)
	v, err := NewVerifierWithKeys("", publicJSON, jwtlib.DefaultIssuer, jwtlib.DefaultAudience, stubBlacklist{blacklisted: true})
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := v.Verify(context.Background(), token); err == nil {
		t.Fatal("expected blacklisted token to be rejected")
	}
}

func TestVerifierAcceptsNonBlacklistedToken(t *testing.T) {
	token, publicJSON := ed25519VerifierMaterial(t)
	v, err := NewVerifierWithKeys("", publicJSON, jwtlib.DefaultIssuer, jwtlib.DefaultAudience, stubBlacklist{blacklisted: false})
	if err != nil {
		t.Fatal(err)
	}
	accountID, tier, err := v.Verify(context.Background(), token)
	if err != nil {
		t.Fatalf("verify: %v", err)
	}
	if accountID != "acc-test" || tier != "free" {
		t.Fatalf("accountID=%q tier=%q", accountID, tier)
	}
}
