package peer

import (
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/veritasvpn/services/veritas-agent/internal/wireguard"
)

type PeerConfig struct {
	PeerID       string
	PublicKey    string
	PresharedKey string
	AllowedIPs   []string
}

type Manager struct {
	wg    *wireguard.Manager
	mu    sync.RWMutex
	peers map[string]*PeerConfig
}

func New(wgManager *wireguard.Manager) *Manager {
	return &Manager{wg: wgManager, peers: make(map[string]*PeerConfig)}
}

func (m *Manager) AddPeer(peerID, pubkey, psk string, allowedIPs []string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	ipNets := cidrsToIPNets(allowedIPs)
	if len(ipNets) == 0 {
		return fmt.Errorf("peer add %s has no valid allowed IPs", peerID)
	}
	var pskPtr *string
	if psk != "" {
		pskPtr = &psk
	}
	if err := m.wg.AddPeer(pubkey, ipNets, pskPtr); err != nil {
		return fmt.Errorf("peer add %s: %w", peerID, err)
	}
	m.peers[pubkey] = &PeerConfig{PeerID: peerID, PublicKey: pubkey, PresharedKey: psk, AllowedIPs: allowedIPs}
	return nil
}

func (m *Manager) RemovePeer(pubkey string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if err := m.wg.RemovePeer(pubkey); err != nil {
		return fmt.Errorf("peer remove %s: %w", pubkey, err)
	}
	delete(m.peers, pubkey)
	return nil
}

func (m *Manager) SyncPeers(desired []PeerConfig) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	want := make(map[string]*PeerConfig, len(desired))
	for i := range desired {
		want[desired[i].PublicKey] = &desired[i]
	}
	kernelPeers, err := m.wg.ListPeers()
	if err != nil {
		return fmt.Errorf("list kernel peers: %w", err)
	}
	existing := make(map[string]struct{}, len(kernelPeers))
	for _, p := range kernelPeers {
		existing[p.PublicKey] = struct{}{}
	}
	for pubkey := range existing {
		if _, ok := want[pubkey]; !ok {
			if err := m.wg.RemovePeer(pubkey); err != nil {
				return fmt.Errorf("sync remove %s: %w", pubkey, err)
			}
		}
	}
	for pubkey, cfg := range want {
		ipNets := cidrsToIPNets(cfg.AllowedIPs)
		if len(ipNets) == 0 {
			return fmt.Errorf("sync peer %s has no valid allowed IPs", pubkey)
		}
		var pskPtr *string
		if cfg.PresharedKey != "" {
			pskPtr = &cfg.PresharedKey
		}
		if err := m.wg.AddPeer(pubkey, ipNets, pskPtr); err != nil {
			return fmt.Errorf("sync apply %s: %w", pubkey, err)
		}
	}
	m.peers = want
	return nil
}

func (m *Manager) GetStats() (rxBytes, txBytes int64, peerCount, activePeerCount int32) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	wgPeers, err := m.wg.ListPeers()
	if err != nil {
		return 0, 0, int32(len(m.peers)), 0
	}
	activeCutoff := time.Now().Add(-3 * time.Minute)
	for _, p := range wgPeers {
		rxBytes += p.RXBytes
		txBytes += p.TXBytes
		if !p.LastHandshakeAt.IsZero() && p.LastHandshakeAt.After(activeCutoff) {
			activePeerCount++
		}
	}
	return rxBytes, txBytes, int32(len(m.peers)), activePeerCount
}

func cidrsToIPNets(cidrs []string) []net.IPNet {
	nets := make([]net.IPNet, 0, len(cidrs))
	for _, raw := range cidrs {
		c := raw
		if !strings.Contains(c, "/") {
			c += "/32"
		}
		_, n, err := net.ParseCIDR(c)
		if err == nil {
			nets = append(nets, *n)
		}
	}
	return nets
}
