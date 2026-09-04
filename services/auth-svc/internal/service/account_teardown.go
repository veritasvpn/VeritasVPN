package service

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/nats-io/nats.go"
	"github.com/veritasvpn/lib/logging"
	"go.uber.org/zap"
)

// accountTeardownSubject must match wg-manager's AccountTeardownSubject.
const accountTeardownSubject = "account.teardown"

type accountTeardownRequest struct {
	AccountID string `json:"account_id"`
}

type accountTeardownResponse struct {
	OK           bool   `json:"ok"`
	Error        string `json:"error,omitempty"`
	PeersRemoved int    `json:"peers_removed,omitempty"`
}

// requestAccountTeardown asks wg-manager to REMOVE live peers/port-forwards
// before the account row is deleted. Fail-closed when NATS is connected so a
// deleted account cannot leave an active tunnel.
func (s *AuthService) requestAccountTeardown(ctx context.Context, accountID string) error {
	if s.nats == nil {
		s.log.Warn("NATS unavailable; skipping VPN teardown on account delete",
			zap.String("account_hash", logging.HashIdentifier(accountID)))
		return nil
	}

	payload, err := json.Marshal(accountTeardownRequest{AccountID: accountID})
	if err != nil {
		return fmt.Errorf("marshal teardown request: %w", err)
	}

	timeout := 45 * time.Second
	if deadline, ok := ctx.Deadline(); ok {
		if remaining := time.Until(deadline); remaining > 0 && remaining < timeout {
			timeout = remaining
		}
	}

	msg, err := s.nats.Request(accountTeardownSubject, payload, timeout)
	if err != nil {
		if err == nats.ErrNoResponders {
			return fmt.Errorf("vpn teardown unavailable: no wg-manager responders")
		}
		return fmt.Errorf("vpn teardown request: %w", err)
	}

	var resp accountTeardownResponse
	if err := json.Unmarshal(msg.Data, &resp); err != nil {
		return fmt.Errorf("vpn teardown response: %w", err)
	}
	if !resp.OK {
		if resp.Error == "" {
			resp.Error = "teardown failed"
		}
		return fmt.Errorf("vpn teardown: %s", resp.Error)
	}

	s.log.Info("vpn teardown acknowledged",
		zap.String("account_hash", logging.HashIdentifier(accountID)),
		zap.Int("peers_removed", resp.PeersRemoved),
	)
	return nil
}

// blacklistAccessToken revokes the caller's access JWT for the remainder of its TTL.
func (s *AuthService) blacklistAccessToken(ctx context.Context, accessToken string) error {
	accessToken = strings.TrimSpace(accessToken)
	if accessToken == "" {
		return nil
	}
	ttl := s.cfg.AccessTokenTTL
	if claims, err := s.jwt.ValidateAccessToken(accessToken); err == nil && claims.ExpiresAt != nil {
		if remaining := time.Until(claims.ExpiresAt.Time); remaining > 0 {
			ttl = remaining
		}
	}
	if ttl <= 0 {
		return nil
	}
	if err := s.redis.BlacklistToken(ctx, hashInput(accessToken), ttl); err != nil {
		return fmt.Errorf("blacklist access token: %w", err)
	}
	return nil
}
