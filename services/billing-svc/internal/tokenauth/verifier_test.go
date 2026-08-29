package tokenauth

import (
	"context"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

type stubBlacklist struct {
	blacklisted bool
}

func (s stubBlacklist) IsTokenBlacklisted(_ context.Context, _ string) (bool, error) {
	return s.blacklisted, nil
}

func TestVerifierRejectsBlacklistedToken(t *testing.T) {
	secret := "test-jwt-secret-minimum-length"
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"account_id": "acc-test",
		"sub":        "acc-test",
		"iss":        "veritasvpn",
		"tier":       "free",
		"exp":        time.Now().Add(time.Hour).Unix(),
	})
	tokenStr, err := token.SignedString([]byte(secret))
	if err != nil {
		t.Fatal(err)
	}

	v := NewVerifier(secret, stubBlacklist{blacklisted: true})
	if _, _, err := v.Verify(context.Background(), tokenStr); err == nil {
		t.Fatal("expected blacklisted token to be rejected")
	}
}

func TestVerifierAcceptsNonBlacklistedToken(t *testing.T) {
	secret := "test-jwt-secret-minimum-length"
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{
		"account_id": "acc-test",
		"sub":        "acc-test",
		"iss":        "veritasvpn",
		"tier":       "free",
		"exp":        time.Now().Add(time.Hour).Unix(),
	})
	tokenStr, err := token.SignedString([]byte(secret))
	if err != nil {
		t.Fatal(err)
	}

	v := NewVerifier(secret, stubBlacklist{blacklisted: false})
	accountID, tier, err := v.Verify(context.Background(), tokenStr)
	if err != nil {
		t.Fatalf("verify: %v", err)
	}
	if accountID != "acc-test" || tier != "free" {
		t.Fatalf("accountID=%q tier=%q", accountID, tier)
	}
}
