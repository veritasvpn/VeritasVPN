package service

import (
	"context"
	"encoding/json"
	"fmt"
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
// before the account row is deleted. It fails closed: unless wg-manager
// acknowledges, the caller must not delete the account, or the user would be
// told their account is gone while their tunnel keeps working.
func (s *AuthService) requestAccountTeardown(ctx context.Context, accountID string) error {
	if s.nats == nil {
		// Production refuses to start without NATS, so reaching this in
		// production means the wiring is broken; deleting anyway would strand a
		// live peer. Local development has no VPN plane to tear down.
		if s.cfg.IsProduction() {
			return fmt.Errorf("vpn teardown unavailable: no NATS connection")
		}
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
