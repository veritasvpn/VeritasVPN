package hub

import (
	"encoding/json"
	"sync"

	"github.com/veritasvpn/lib/logging"
)

// PeerUpdate is the SSE payload consumed by veritas-agent.
type PeerUpdate struct {
	Action       string   `json:"action"`
	PeerID       string   `json:"peer_id"`
	PublicKey    string   `json:"public_key"`
	PresharedKey string   `json:"preshared_key"`
	AllowedIPs   []string `json:"allowed_ips"`
}

type subscriber struct {
	ch chan PeerUpdate
}

// Hub fans peer updates out to agents subscribed by server ID.
type Hub struct {
	mu   sync.RWMutex
	subs map[string]map[*subscriber]struct{}
	log  *logging.Logger
}

func New(log *logging.Logger) *Hub {
	return &Hub{
		subs: make(map[string]map[*subscriber]struct{}),
		log:  log,
	}
}

func (h *Hub) Subscribe(serverID string) (<-chan PeerUpdate, func()) {
	sub := &subscriber{ch: make(chan PeerUpdate, 32)}

	h.mu.Lock()
	if h.subs[serverID] == nil {
		h.subs[serverID] = make(map[*subscriber]struct{})
	}
	h.subs[serverID][sub] = struct{}{}
	h.mu.Unlock()

	unsubscribe := func() {
		h.mu.Lock()
		defer h.mu.Unlock()
		if set, ok := h.subs[serverID]; ok {
			delete(set, sub)
			if len(set) == 0 {
				delete(h.subs, serverID)
			}
		}
		close(sub.ch)
	}

	return sub.ch, unsubscribe
}

// Publish fans an update to subscribers. Returns false when no agent is
// connected (or every subscriber channel is full).
func (h *Hub) Publish(serverID string, update PeerUpdate) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()

	set := h.subs[serverID]
	if len(set) == 0 {
		h.log.Warn("no agent subscribers for peer update",
			"server_id", serverID,
			"action", update.Action,
			"peer_id", update.PeerID,
		)
		return false
	}

	delivered := false
	for sub := range set {
		select {
		case sub.ch <- update:
			delivered = true
		default:
			h.log.Warn("dropping peer update; subscriber slow",
				"server_id", serverID,
				"peer_id", update.PeerID,
			)
		}
	}
	return delivered
}

// EncodeSSE formats a peer update as an SSE data line.
func EncodeSSE(update PeerUpdate) ([]byte, error) {
	payload, err := json.Marshal(update)
	if err != nil {
		return nil, err
	}
	out := make([]byte, 0, len(payload)+8)
	out = append(out, "data: "...)
	out = append(out, payload...)
	out = append(out, '\n', '\n')
	return out, nil
}
