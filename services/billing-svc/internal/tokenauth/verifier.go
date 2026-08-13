package tokenauth

import (
	"context"
	"errors"
	"fmt"

	"github.com/golang-jwt/jwt/v5"
)

type Verifier struct {
	secret []byte
}

func NewVerifier(secret string) *Verifier {
	return &Verifier{secret: []byte(secret)}
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

	return claims.AccountID, claims.Tier, nil
}
