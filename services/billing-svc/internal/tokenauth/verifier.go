package tokenauth

import (
	"context"
	"errors"
	"fmt"

	"github.com/golang-jwt/jwt/v5"
	"github.com/veritasvpn/lib/tokenhash"
)

type TokenBlacklist interface {
	IsTokenBlacklisted(ctx context.Context, tokenHash string) (bool, error)
}

type Verifier struct {
	secret    []byte
	blacklist TokenBlacklist
}

func NewVerifier(secret string, blacklist TokenBlacklist) *Verifier {
	return &Verifier{secret: []byte(secret), blacklist: blacklist}
}

type veritasClaims struct {
	AccountID string `json:"account_id"`
	Tier      string `json:"tier"`
	jwt.RegisteredClaims
}

func (v *Verifier) Verify(ctx context.Context, tokenStr string) (string, string, error) {
	if tokenStr == "" {
		return "", "", errors.New("missing token")
	}

	token, err := jwt.ParseWithClaims(tokenStr, &veritasClaims{}, func(t *jwt.Token) (interface{}, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
		return v.secret, nil
	})
	if err != nil {
		return "", "", fmt.Errorf("parse token: %w", err)
	}

	claims, ok := token.Claims.(*veritasClaims)
	if !ok || !token.Valid {
		return "", "", errors.New("invalid token claims")
	}

	if claims.AccountID == "" {
		return "", "", errors.New("missing account_id")
	}

	if v.blacklist != nil {
		blacklisted, err := v.blacklist.IsTokenBlacklisted(ctx, tokenhash.Hash(tokenStr))
		if err != nil {
			return "", "", fmt.Errorf("blacklist check: %w", err)
		}
		if blacklisted {
			return "", "", errors.New("token revoked")
		}
	}

	return claims.AccountID, claims.Tier, nil
}
