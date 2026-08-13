package peer

import (
	"fmt"
	"net"
	"strings"
	"sync"

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
	return &Manager{
		wg:    wgManager,
		peers: make(map[string]*PeerConfig),
	}
}

func (m *Manager) AddPeer(peerID, pubkey, psk string, allowedIPs []string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	ipNets := cidrsToIPNets(allowedIPs)

	var pskPtr *string
	if psk != "" {
		pskPtr = &psk
	}

	if err := m.wg.AddPeer(pubkey, ipNets, pskPtr); err != nil {
		return fmt.Errorf("peer add %s: %w", peerID, err)
	}

	m.peers[pubkey] = &PeerConfig{
		PeerID:       peerID,
		PublicKey:    pubkey,
		PresharedKey: psk,
		AllowedIPs:   allowedIPs,
	}

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

	// The in-memory map is only a cache and is empty after an agent restart.
	// Reconcile against the kernel device so stale peers cannot survive a
	// process/container restart indefinitely.
	kernelPeers, err := m.wg.ListPeers()
	if err != nil {
		return fmt.Errorf("list kernel peers: %w", err)
	}

	existing := make(map[string]struct{}, len(kernelPeers))
	for _, peer := range kernelPeers {
		existing[peer.PublicKey] = struct{}{}
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

		// AddPeer uses ReplaceAllowedIPs, so applying every desired peer also
		// repairs malformed or outdated kernel configuration.
		if err := m.wg.AddPeer(pubkey, ipNets, pskPtr); err != nil {
			return fmt.Errorf("sync apply %s: %w", pubkey, err)
		}
	}

	m.peers = want

	return nil
}

func (m *Manager) GetStats() (rxBytes, txBytes int64, peerCount int32) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	wgPeers, err := m.wg.ListPeers()
	if err != nil {
		return 0, 0, int32(len(m.peers))
	}

	for _, p := range wgPeers {
		rxBytes += p.RXBytes
		txBytes += p.TXBytes
	}

	return rxBytes, txBytes, int32(len(m.peers))
}

func cidrsToIPNets(cidrs []string) []net.IPNet {
	ipNets := make([]net.IPNet, 0, len(cidrs))
	for _, cidr := range cidrs {
		c := cidr
		if !strings.Contains(c, "/") {
			c = c + "/32"
		}
		_, ipNet, err := net.ParseCIDR(c)
		if err != nil {
			continue
		}
		ipNets = append(ipNets, *ipNet)
	}
	return ipNets
}
