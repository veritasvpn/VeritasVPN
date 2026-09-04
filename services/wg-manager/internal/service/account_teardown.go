package service

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/nats-io/nats.go"
	"github.com/veritasvpn/lib/logging"
)

// AccountTeardownSubject is the NATS request-reply subject auth-svc uses before
// permanently deleting an account. wg-manager removes live peers/port-forwards
// via the agent so tunnels cannot outlive the account.
const AccountTeardownSubject = "account.teardown"

// AccountTeardownRequest is published by auth-svc.
type AccountTeardownRequest struct {
	AccountID string `json:"account_id"`
}

// AccountTeardownResponse is returned by wg-manager.
type AccountTeardownResponse struct {
	OK           bool   `json:"ok"`
	Error        string `json:"error,omitempty"`
	PeersRemoved int    `json:"peers_removed,omitempty"`
}

// TeardownAccount removes every non-removed peer (and its port-forwards) for
// the account, notifying the agent with REMOVE / PORT_FORWARD_REMOVE first.
func (s *Service) TeardownAccount(ctx context.Context, accountID string) (int, error) {
	if accountID == "" {
		return 0, fmt.Errorf("account_id is required")
	}
	peers, err := s.postgres.ListPeersByAccount(ctx, accountID)
	if err != nil {
		return 0, fmt.Errorf("list peers for teardown: %w", err)
	}
	removed := 0
	var firstErr error
	for i := range peers {
		peer := peers[i]
		if err := s.DeletePeer(ctx, peer.ID, accountID); err != nil {
			s.log.Error("account teardown peer delete failed",
				"account_hash", logging.HashIdentifier(accountID),
				"peer_hash", logging.HashIdentifier(peer.ID),
				"error", err,
			)
			if firstErr == nil {
				firstErr = err
			}
			continue
		}
		removed++
	}
	if firstErr != nil {
		return removed, fmt.Errorf("teardown incomplete (%d/%d peers removed): %w", removed, len(peers), firstErr)
	}
	return removed, nil
}

// StartAccountTeardownSync listens for auth-svc account.teardown requests.
func (s *Service) StartAccountTeardownSync(nc *nats.Conn) error {
	if nc == nil {
		return nil
	}
	_, err := nc.QueueSubscribe(AccountTeardownSubject, "wg-manager", func(msg *nats.Msg) {
		var req AccountTeardownRequest
		if err := json.Unmarshal(msg.Data, &req); err != nil || req.AccountID == "" {
			s.respondTeardown(msg, AccountTeardownResponse{OK: false, Error: "invalid teardown request"})
			return
		}
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		n, err := s.TeardownAccount(ctx, req.AccountID)
		if err != nil {
			s.respondTeardown(msg, AccountTeardownResponse{OK: false, Error: err.Error(), PeersRemoved: n})
			return
		}
		s.log.Info("account teardown complete",
			"account_hash", logging.HashIdentifier(req.AccountID),
			"peers_removed", n,
		)
		s.respondTeardown(msg, AccountTeardownResponse{OK: true, PeersRemoved: n})
	})
	return err
}

func (s *Service) respondTeardown(msg *nats.Msg, resp AccountTeardownResponse) {
	if msg.Reply == "" {
		return
	}
	data, err := json.Marshal(resp)
	if err != nil {
		return
	}
	_ = msg.Respond(data)
}
