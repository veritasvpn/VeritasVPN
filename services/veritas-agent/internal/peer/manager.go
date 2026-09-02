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
	AddedAt      time.Time
}

type Manager struct {
	wg              *wireguard.Manager
	mu              sync.RWMutex
	peers           map[string]*PeerConfig
	lastStaleReport map[string]time.Time
}

func New(wgManager *wireguard.Manager) *Manager {
	return &Manager{wg: wgManager, peers: make(map[string]*PeerConfig), lastStaleReport: make(map[string]time.Time)}
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
	addedAt := time.Now()
	if existing, ok := m.peers[pubkey]; ok && !existing.AddedAt.IsZero() {
		addedAt = existing.AddedAt
	}
	m.peers[pubkey] = &PeerConfig{PeerID: peerID, PublicKey: pubkey, PresharedKey: psk, AllowedIPs: allowedIPs, AddedAt: addedAt}
	return nil
}

// RemovePeer removes a peer from the kernel and local map.
// It returns the peer's AllowedIPs (if known) so callers can clear
// DNS block counters tied to those tunnel addresses.
func (m *Manager) RemovePeer(pubkey string) (allowedIPs []string, err error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if cfg := m.peers[pubkey]; cfg != nil {
		allowedIPs = append([]string(nil), cfg.AllowedIPs...)
	}
	kernelPeers, err := m.wg.ListPeers()
	if err != nil {
		return allowedIPs, fmt.Errorf("list peers before remove %s: %w", pubkey, err)
	}
	for _, p := range kernelPeers {
		if p.PublicKey == pubkey {
			if err := m.wg.RemovePeer(pubkey); err != nil {
				return allowedIPs, fmt.Errorf("peer remove %s: %w", pubkey, err)
			}
			break
		}
	}
	delete(m.peers, pubkey)
	delete(m.lastStaleReport, pubkey)
	return allowedIPs, nil
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
			delete(m.lastStaleReport, pubkey)
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
	for pubkey, cfg := range want {
		if existingCfg, ok := m.peers[pubkey]; ok && !existingCfg.AddedAt.IsZero() {
			cfg.AddedAt = existingCfg.AddedAt
		}
		if cfg.AddedAt.IsZero() {
			cfg.AddedAt = time.Now()
		}
	}
	m.peers = want
	return nil
}

// StalePeers returns peers that stopped handshaking. Clients use a 25-second
// keepalive, so a multi-minute grace period avoids removing healthy peers
// during a short network transition while still cleaning abandoned sessions.
// ReconcileOrphans removes kernel peers that are no longer represented in the
// manager stream. A grace period protects peers while the initial SSE catch-up
// is still being applied after an agent restart.
func (m *Manager) ReconcileOrphans(now time.Time, orphanAfter time.Duration) (removed, remaining int, err error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	kernelPeers, err := m.wg.ListPeers()
	if err != nil {
		return 0, 0, err
	}
	for _, kernel := range kernelPeers {
		if _, known := m.peers[kernel.PublicKey]; known {
			continue
		}
		if kernel.LastHandshakeAt.IsZero() || now.Sub(kernel.LastHandshakeAt) >= orphanAfter {
			if removeErr := m.wg.RemovePeer(kernel.PublicKey); removeErr != nil {
				err = removeErr
				remaining++
				continue
			}
			removed++
			continue
		}
		remaining++
	}
	return removed, remaining, err
}

func (m *Manager) StalePeers(now time.Time, noHandshakeGrace, staleAfter time.Duration) []PeerConfig {
	m.mu.Lock()
	defer m.mu.Unlock()
	kernelPeers, err := m.wg.ListPeers()
	if err != nil {
		return nil
	}
	stale := make([]PeerConfig, 0)
	for _, kernel := range kernelPeers {
		cfg, ok := m.peers[kernel.PublicKey]
		if !ok {
			continue
		}
		age := now.Sub(kernel.LastHandshakeAt)
		if kernel.LastHandshakeAt.IsZero() {
			age = now.Sub(cfg.AddedAt)
			if age < noHandshakeGrace {
				continue
			}
		} else if age < staleAfter {
			continue
		}
		if last := m.lastStaleReport[kernel.PublicKey]; !last.IsZero() && now.Sub(last) < 2*time.Minute {
			continue
		}
		m.lastStaleReport[kernel.PublicKey] = now
		stale = append(stale, *cfg)
	}
	return stale
}

func (m *Manager) StalePeerCount(now time.Time, noHandshakeGrace, staleAfter time.Duration) int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	kernelPeers, err := m.wg.ListPeers()
	if err != nil {
		return 0
	}
	count := 0
	for _, kernel := range kernelPeers {
		cfg, ok := m.peers[kernel.PublicKey]
		if !ok {
			continue
		}
		age := now.Sub(kernel.LastHandshakeAt)
		if kernel.LastHandshakeAt.IsZero() {
			age = now.Sub(cfg.AddedAt)
			if age >= noHandshakeGrace {
				count++
			}
		} else if age >= staleAfter {
			count++
		}
	}
	return count
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
	return rxBytes, txBytes, int32(len(wgPeers)), activePeerCount
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
