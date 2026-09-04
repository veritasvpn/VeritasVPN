package communicator

import (
	"context"
	"fmt"
	"math"
	"strings"
	"time"

	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/wg-manager/internal/hub"
	"github.com/veritasvpn/services/wg-manager/internal/model"
)

type AgentClient interface {
	PushPeerUpdate(ctx context.Context, serverID string, action string, peerID string, publicKey string, presharedKey string, allowedIPs []string, shieldPreset string) error
	Publish(serverID string, update hub.PeerUpdate) bool
}

type Communicator struct {
	client AgentClient
	log    *logging.Logger
}

func New(client AgentClient, log *logging.Logger) *Communicator {
	return &Communicator{
		client: client,
		log:    log,
	}
}

func (c *Communicator) PushPeerAdded(ctx context.Context, serverID string, peer *model.Peer) error {
	psk := ""
	if peer.PresharedKey != nil {
		psk = *peer.PresharedKey
	}
	return c.pushWithBackoff(ctx, serverID, "ADD", peer.ID, peer.Pubkey, psk, peer.AllowedIPs, peer.ShieldPreset)
}

func (c *Communicator) PushPeerRemoved(ctx context.Context, serverID string, peer *model.Peer) error {
	ips := peer.AllowedIPs
	if len(ips) == 0 && peer.AssignedIP != "" {
		ips = []string{peer.AssignedIP}
	}
	return c.pushWithBackoff(ctx, serverID, "REMOVE", peer.ID, peer.Pubkey, "", ips, "")
}

// PushShieldPreset notifies the agent of a per-peer Veritas Shield policy change
// without re-applying the WireGuard peer.
func (c *Communicator) PushShieldPreset(serverID string, peer *model.Peer) bool {
	ips := peer.AllowedIPs
	if len(ips) == 0 && peer.AssignedIP != "" {
		ips = []string{peer.AssignedIP}
	}
	return c.PublishUpdate(serverID, hub.PeerUpdate{
		Action:       "SHIELD_PRESET",
		PeerID:       peer.ID,
		AllowedIPs:   ips,
		AssignedIP:   peer.AssignedIP,
		ShieldPreset: peer.ShieldPreset,
	})
}

// PublishUpdate fans an SSE payload once (no retries). Returns whether any
// agent subscriber received it.
func (c *Communicator) PublishUpdate(serverID string, update hub.PeerUpdate) bool {
	ok := c.client.Publish(serverID, update)
	if ok {
		c.log.Info("update published to agent",
			"server_id", serverID,
			"action", update.Action,
			"peer_id", update.PeerID,
			"forward_id", update.ForwardID,
		)
	} else {
		c.log.Warn("no agent connected for update",
			"server_id", serverID,
			"action", update.Action,
			"peer_id", update.PeerID,
			"forward_id", update.ForwardID,
		)
	}
	return ok
}

func (c *Communicator) PushPortForwardAdd(serverID string, pf *model.PortForward) bool {
	return c.PublishUpdate(serverID, hub.PeerUpdate{
		Action:       "PORT_FORWARD_ADD",
		PeerID:       pf.PeerID,
		ForwardID:    pf.ID,
		Protocol:     pf.Protocol,
		ExternalPort: pf.ExternalPort,
		InternalPort: pf.InternalPort,
		AssignedIP:   pf.AssignedIP,
	})
}

func (c *Communicator) PushPortForwardRemove(serverID string, pf *model.PortForward) bool {
	return c.PublishUpdate(serverID, hub.PeerUpdate{
		Action:       "PORT_FORWARD_REMOVE",
		PeerID:       pf.PeerID,
		ForwardID:    pf.ID,
		Protocol:     pf.Protocol,
		ExternalPort: pf.ExternalPort,
		InternalPort: pf.InternalPort,
		AssignedIP:   pf.AssignedIP,
	})
}

func (c *Communicator) pushWithBackoff(ctx context.Context, serverID, action, peerID, pubkey, psk string, allowedIPs []string, shieldPreset string) error {
	maxRetries := 3
	baseDelay := 200 * time.Millisecond

	var lastErr error
	for i := 0; i < maxRetries; i++ {
		callCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		err := c.client.PushPeerUpdate(callCtx, serverID, action, peerID, pubkey, psk, allowedIPs, shieldPreset)
		cancel()

		if err == nil {
			c.log.Info("peer update pushed to agent",
				"server_id", serverID,
				"action", action,
				"attempt", i+1,
			)
			return nil
		}

		lastErr = err
		c.log.Warn("agent push failed, retrying",
			"attempt", i+1,
			"server_id", serverID,
			"action", action,
			"error", err.Error(),
		)

		if i < maxRetries-1 {
			delay := time.Duration(float64(baseDelay) * math.Pow(2, float64(i)))
			select {
			case <-time.After(delay):
			case <-ctx.Done():
				return ctx.Err()
			}
		}
	}

	c.log.Error("agent push failed after all retries",
		"server_id", serverID,
		"action", action,
		"error", lastErr,
	)
	return lastErr
}

// SSEAgentClient publishes peer updates to connected agents via the SSE hub.
type SSEAgentClient struct {
	hub *hub.Hub
	log *logging.Logger
}

func NewSSEAgentClient(h *hub.Hub, log *logging.Logger) AgentClient {
	return &SSEAgentClient{hub: h, log: log}
}

func (s *SSEAgentClient) Publish(serverID string, update hub.PeerUpdate) bool {
	return s.hub.Publish(serverID, update)
}

func (s *SSEAgentClient) PushPeerUpdate(ctx context.Context, serverID string, action string, peerID string, publicKey string, presharedKey string, allowedIPs []string, shieldPreset string) error {
	_ = ctx
	ok := s.Publish(serverID, hub.PeerUpdate{
		Action:       strings.ToUpper(action),
		PeerID:       peerID,
		PublicKey:    publicKey,
		PresharedKey: presharedKey,
		AllowedIPs:   allowedIPs,
		ShieldPreset: shieldPreset,
	})
	if !ok {
		return fmt.Errorf("no agent connected for server %s", serverID)
	}
	return nil
}
