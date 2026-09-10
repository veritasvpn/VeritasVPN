package tokenauth

import (
	"context"
	"errors"
	"fmt"
	"time"

	jwtlib "github.com/veritasvpn/lib/jwt"
	"github.com/veritasvpn/lib/tokenhash"
)

type TokenBlacklist interface {
	IsTokenBlacklisted(ctx context.Context, tokenHash string) (bool, error)
	GetAccountSessionVersion(ctx context.Context, accountID string) (int64, error)
}

type Verifier struct {
	manager   *jwtlib.Manager
	blacklist TokenBlacklist
}

func NewVerifier(secret string, blacklist TokenBlacklist) *Verifier {
	return &Verifier{manager: jwtlib.NewManager(secret, time.Hour, 0), blacklist: blacklist}
}

func NewVerifierWithKeys(secret, publicKeysJSON, issuer, audience string, blacklist TokenBlacklist) (*Verifier, error) {
	manager, err := jwtlib.NewManagerWithKeys(secret, "", publicKeysJSON, "", issuer, audience, time.Hour)
	if err != nil {
		return nil, err
	}
	return &Verifier{manager: manager, blacklist: blacklist}, nil
}

func (v *Verifier) Verify(ctx context.Context, tokenStr string) (string, string, error) {
	if tokenStr == "" {
		return "", "", errors.New("missing token")
	}

	claims, err := v.manager.ValidateAccessToken(tokenStr)
	if err != nil {
		return "", "", fmt.Errorf("parse token: %w", err)
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
		version, err := v.blacklist.GetAccountSessionVersion(ctx, claims.AccountID)
		if err != nil {
			return "", "", fmt.Errorf("session version check: %w", err)
		}
		if version != claims.SessionVersion {
			return "", "", errors.New("account sessions revoked")
		}
	}

	return claims.AccountID, claims.Tier, nil
}
