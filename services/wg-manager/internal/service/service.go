package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/nats-io/nats.go"
	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/wg-manager/internal/communicator"
	"github.com/veritasvpn/services/wg-manager/internal/entitlement"
	"github.com/veritasvpn/services/wg-manager/internal/model"
	"github.com/veritasvpn/services/wg-manager/internal/repository"
	"github.com/veritasvpn/services/wg-manager/internal/scheduler"
)

type PeerConfig struct {
	PeerID                 string
	ServerID               string
	ServerHostname         string
	ServerPublicKey        string
	ServerEndpoint         string
	StealthEndpoint        string
	StealthAvailable       bool
	StealthPathPrefix      string
	AssignedIP             string
	DNSServer              string
	PresharedKey           string
	AllowedIPs             []string // server-side peer AllowedIPs (client /32)
	ClientAllowedIPs       []string // client tunnel AllowedIPs (full tunnel)
	PersistentKeepaliveSec int
	DeviceID               string
}

type Service struct {
	postgres          *repository.Postgres
	redis             *repository.Redis
	scheduler         *scheduler.Scheduler
	communicator      *communicator.Communicator
	natsConn          *nats.Conn
	authToken         string
	freeRegions       []string
	lanIP             string
	lanPort           int32
	stealthHost       string
	stealthPort       int32
	stealthPathPrefix string
	stealthAvailable  bool
	tierCache         *entitlement.TierCache
	log               *logging.Logger
}

func New(
	postgres *repository.Postgres,
	redis *repository.Redis,
	scheduler *scheduler.Scheduler,
	communicator *communicator.Communicator,
	natsConn *nats.Conn,
	authToken string,
	tierCache *entitlement.TierCache,
	log *logging.Logger,
) *Service {
	return &Service{
		postgres:     postgres,
		redis:        redis,
		scheduler:    scheduler,
		communicator: communicator,
		natsConn:     natsConn,
		authToken:    authToken,
		tierCache:    tierCache,
		log:          log,
	}
}

// SetFreeAllowedRegions configures optional Free-plan region allow-list
// (from FREE_ALLOWED_REGIONS). Empty means any online region.
func (s *Service) SetFreeAllowedRegions(regions []string) {
	s.freeRegions = regions
}

// SetLANEndpoint configures an alternate WireGuard endpoint advertised to
// clients that are already on the VPN node's LAN (or whose public IP matches
// the node). This avoids slow router hairpin NAT when the public endpoint is
// used from the same network.
func (s *Service) SetLANEndpoint(ip string, port int32) {
	s.lanIP = strings.TrimSpace(ip)
	s.lanPort = port
}

// SetStealthEndpoint configures the optional TLS/WebSocket wrapper endpoint
// (wstunnel). Clients that enable stealth mode dial this instead of raw UDP.
func (s *Service) SetStealthEndpoint(host string, port int32, pathPrefix string) {
	s.stealthHost = strings.TrimSpace(host)
	s.stealthPort = port
	s.stealthPathPrefix = strings.TrimSpace(pathPrefix)
	s.stealthAvailable = s.stealthHost != "" && s.stealthPort > 0 && s.stealthPathPrefix != ""
}

// ClientEndpoint returns the WireGuard endpoint a client should dial.
func (s *Service) ClientEndpoint(srv *model.Server, clientIP string) string {
	if srv == nil {
		return ""
	}
	if s.useLANEndpoint(clientIP, srv.PublicIP) {
		return fmt.Sprintf("%s:%d", s.lanIP, s.lanPort)
	}
	return fmt.Sprintf("%s:%d", srv.PublicIP, srv.WGPort)
}

// ClientStealthEndpoint returns host:port for the TLS stealth transport, or "".
func (s *Service) ClientStealthEndpoint(srv *model.Server, clientIP string) string {
	if !s.stealthAvailable || srv == nil {
		return ""
	}
	host := s.stealthHost
	if s.useLANEndpoint(clientIP, srv.PublicIP) && s.lanIP != "" {
		host = s.lanIP
	}
	return fmt.Sprintf("%s:%d", host, s.stealthPort)
}

// StealthAvailable reports whether stealth transport is configured.
func (s *Service) StealthAvailable() bool {
	return s.stealthAvailable
}

// StealthPathPrefix returns the shared HTTP upgrade path prefix for wstunnel.
func (s *Service) StealthPathPrefix() string {
	return s.stealthPathPrefix
}

func (s *Service) useLANEndpoint(clientIP, publicIP string) bool {
	if s.lanIP == "" || s.lanPort <= 0 || clientIP == "" {
		return false
	}
	clientIP = strings.TrimSpace(clientIP)
	if clientIP == publicIP {
		return true
	}
	cip := net.ParseIP(clientIP)
	lip := net.ParseIP(s.lanIP)
	if cip == nil || lip == nil {
		return false
	}
	c4, l4 := cip.To4(), lip.To4()
	if c4 == nil || l4 == nil {
		return false
	}
	// Same /24 as the VPN node's LAN address.
	return c4[0] == l4[0] && c4[1] == l4[1] && c4[2] == l4[2]
}

func (s *Service) resolveTier(accountID, jwtTier string) string {
	jwtTier = entitlement.NormalizeTier(jwtTier)
	if s.tierCache == nil {
		return jwtTier
	}
	cachedTier, ok := s.tierCache.Lookup(accountID)
	if !ok {
		return jwtTier
	}
	if cachedTier != jwtTier {
		s.log.Debug("tier resolved from billing cache",
			"account_id", accountID,
			"jwt_tier", jwtTier,
			"cached_tier", cachedTier,
		)
	}
	return cachedTier
}

func (s *Service) RegisterServer(ctx context.Context, hostname, publicKey, publicIP string, wgPort int32, region, city, country, authToken string) (*model.Server, error) {
	if authToken != s.authToken {
		return nil, fmt.Errorf("invalid agent auth token")
	}

	existing, err := s.postgres.GetServerByHostname(ctx, hostname)
	if err != nil && !strings.Contains(err.Error(), "no rows") {
		return nil, fmt.Errorf("lookup server: %w", err)
	}

	if existing != nil {
		existing.PublicIP = publicIP
		existing.WGPort = wgPort
		existing.PublicKey = publicKey
		existing.Status = "online"
		if region != "" {
			existing.Region = region
		}
		if city != "" {
			existing.City = city
		}
		if country != "" {
			existing.Country = country
		}
		if err := s.postgres.UpdateServerIdentity(ctx, existing); err != nil {
			return nil, fmt.Errorf("update server: %w", err)
		}
		if err := s.postgres.MarkDuplicateServersOffline(ctx, existing.ID, publicIP, publicKey); err != nil {
			s.log.Warn("failed to mark duplicate servers offline", "error", err)
		}
		s.log.Info("server re-registered",
			"server_id", existing.ID,
			"hostname", hostname,
			"subnet", existing.WGSubnet,
		)
		return existing, nil
	}

	subnet, err := s.allocateSubnet(ctx)
	if err != nil {
		return nil, err
	}

	dnsServer := strings.Replace(subnet, ".0/24", ".1", 1)

	srv := &model.Server{
		Hostname:  hostname,
		Region:    region,
		City:      city,
		Country:   country,
		PublicIP:  publicIP,
		WGPort:    wgPort,
		PublicKey: publicKey,
		Status:    "online",
		Capacity:  100,
		WGSubnet:  subnet,
		DNSServer: dnsServer,
	}

	if err := s.postgres.RegisterServer(ctx, srv); err != nil {
		return nil, fmt.Errorf("register server: %w", err)
	}
	if err := s.postgres.MarkDuplicateServersOffline(ctx, srv.ID, publicIP, publicKey); err != nil {
		s.log.Warn("failed to mark duplicate servers offline", "error", err)
	}

	s.log.Info("server registered",
		"server_id", srv.ID,
		"hostname", hostname,
		"subnet", subnet,
	)

	s.publishEvent("server.registered", map[string]interface{}{
		"server_id": srv.ID,
		"hostname":  srv.Hostname,
		"region":    srv.Region,
		"city":      srv.City,
		"country":   srv.Country,
		"subnet":    subnet,
	})

	return srv, nil
}

func (s *Service) HandleHeartbeat(ctx context.Context, serverID string, peerCount int32, loadFactor float64, rxBytes, txBytes int64, dnsBlockedByIP map[string]uint64) error {
	if err := s.postgres.UpdateServerLoad(ctx, serverID, peerCount, loadFactor); err != nil {
		return fmt.Errorf("update server load: %w", err)
	}

	if err := s.postgres.UpdateServerStatus(ctx, serverID, "online"); err != nil {
		s.log.Warn("failed to set server status to online", "server_id", serverID, "error", err)
	}

	if len(dnsBlockedByIP) > 0 {
		if err := s.redis.SetDNSBlockedCounts(ctx, dnsBlockedByIP); err != nil {
			s.log.Warn("failed to store dns blocked counts", "server_id", serverID, "error", err)
		}
	}

	s.log.Debug("heartbeat processed",
		"server_id", serverID,
		"load_factor", loadFactor,
		"peer_count", peerCount,
	)

	s.publishEvent("server.heartbeat", map[string]interface{}{
		"server_id":   serverID,
		"peer_count":  peerCount,
		"load_factor": loadFactor,
		"rx_bytes":    rxBytes,
		"tx_bytes":    txBytes,
	})

	return nil
}

// DNSBlockedCount returns the absolute blocked-query count for a peer's assigned
// tunnel IP (0 if missing). AssignedIP may include a CIDR suffix.
func (s *Service) DNSBlockedCount(ctx context.Context, assignedIP string) uint64 {
	n, err := s.redis.GetDNSBlockedCount(ctx, assignedIP)
	if err != nil {
		return 0
	}
	return n
}

func (s *Service) CreatePeer(ctx context.Context, accountID, tier, publicKey, deviceID, preferredRegion, clientIP string) (*PeerConfig, error) {
	tier = s.resolveTier(accountID, tier)
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		// Legacy clients without a stable install id always insert a new row so
		// they cannot collide on the old UNIQUE(account_id, server_id) path.
		anon, err := generateAnonDeviceID()
		if err != nil {
			return nil, err
		}
		deviceID = anon
	}

	existing, err := s.postgres.ListPeersByAccount(ctx, accountID)
	if err != nil {
		return nil, fmt.Errorf("list peers for entitlement: %w", err)
	}

	var replacedPeer *model.Peer
	for i := range existing {
		if existing[i].DeviceID == deviceID {
			old := existing[i]
			replacedPeer = &old
			break
		}
	}

	countForLimit := len(existing)
	if replacedPeer != nil {
		countForLimit = len(existing) - 1
	}
	if err := entitlement.CheckCreatePeer(tier, countForLimit, preferredRegion, s.freeRegions); err != nil {
		return nil, err
	}

	srv, err := s.scheduler.SelectServer(ctx, preferredRegion)
	if err != nil {
		return nil, err
	}
	if err := entitlement.CheckSelectedRegion(tier, srv.Region, s.freeRegions); err != nil {
		return nil, err
	}

	// Prefer reusing the prior tunnel IP when reconnecting to the same server so
	// Redis bitmaps and client configs stay stable across reconnects.
	var assignedIP string
	if replacedPeer != nil && replacedPeer.ServerID == srv.ID && replacedPeer.AssignedIP != "" {
		assignedIP = stripCIDR(replacedPeer.AssignedIP)
	}
	if assignedIP == "" {
		// Redis is an allocation accelerator, not the source of truth. Its bitmap
		// can be lost, so always reconcile each candidate with PostgreSQL. Used
		// candidates deliberately remain marked in Redis, rebuilding the bitmap as
		// allocations are attempted after a cache reset.
		for attempts := 0; attempts < 254; attempts++ {
			candidate, err := s.redis.AllocateIP(ctx, srv.ID, srv.WGSubnet)
			if err != nil {
				return nil, fmt.Errorf("allocate ip: %w", err)
			}
			used, err := s.postgres.IsActiveIPAssigned(ctx, srv.ID, candidate)
			if err != nil {
				_ = s.redis.ReleaseIP(ctx, srv.ID, candidate)
				return nil, err
			}
			// Replacing this device's own row frees its IP for reuse.
			if used && replacedPeer != nil && replacedPeer.ServerID == srv.ID && stripCIDR(replacedPeer.AssignedIP) == candidate {
				used = false
			}
			if !used {
				assignedIP = candidate
				break
			}
		}
		if assignedIP == "" {
			return nil, fmt.Errorf("no available IPs in subnet %s", srv.WGSubnet)
		}
	}

	psk, err := generatePSK()
	if err != nil {
		if replacedPeer == nil || stripCIDR(replacedPeer.AssignedIP) != assignedIP {
			_ = s.redis.ReleaseIP(ctx, srv.ID, assignedIP)
		}
		return nil, err
	}

	peer := &model.Peer{
		AccountID:    accountID,
		ServerID:     srv.ID,
		DeviceID:     deviceID,
		Pubkey:       publicKey,
		PresharedKey: &psk,
		AllowedIPs:   []string{assignedIP},
		AssignedIP:   assignedIP,
		Status:       "pending",
		CreatedAt:    time.Now(),
	}

	if replacedPeer != nil {
		peer.ID = replacedPeer.ID
		if err := s.postgres.UpdatePeerIdentity(ctx, peer); err != nil {
			if stripCIDR(replacedPeer.AssignedIP) != assignedIP {
				_ = s.redis.ReleaseIP(ctx, srv.ID, assignedIP)
			}
			return nil, fmt.Errorf("update peer: %w", err)
		}
	} else {
		if err := s.postgres.CreatePeer(ctx, peer); err != nil {
			_ = s.redis.ReleaseIP(ctx, srv.ID, assignedIP)
			return nil, fmt.Errorf("create peer: %w", err)
		}
	}

	endpoint := s.ClientEndpoint(srv, clientIP)
	serverID := srv.ID

	// Apply the update before returning the client configuration. Returning
	// before the agent has the peer creates a handshake race: the client can
	// bring up WireGuard while the server still has no matching public key.
	// This synchronous acknowledgement also makes rapid reconnects deterministic.
	syncCtx, syncCancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer syncCancel()
	if replacedPeer != nil {
		oldServerID := replacedPeer.ServerID
		if replacedPeer.Pubkey != peer.Pubkey || oldServerID != serverID {
			if err := s.communicator.PushPeerRemoved(syncCtx, oldServerID, replacedPeer); err != nil {
				_ = s.postgres.DeletePeer(context.Background(), peer.ID, accountID)
				if stripCIDR(replacedPeer.AssignedIP) != assignedIP || oldServerID != serverID {
					_ = s.redis.ReleaseIP(context.Background(), serverID, assignedIP)
				}
				return nil, fmt.Errorf("remove existing WireGuard peer: %w", err)
			}
		}
		if stripCIDR(replacedPeer.AssignedIP) != assignedIP || oldServerID != serverID {
			_ = s.redis.ReleaseIP(syncCtx, oldServerID, replacedPeer.AssignedIP)
		}
	}
	if err := s.communicator.PushPeerAdded(syncCtx, serverID, peer); err != nil {
		_ = s.postgres.DeletePeer(context.Background(), peer.ID, accountID)
		_ = s.redis.ReleaseIP(context.Background(), serverID, assignedIP)
		return nil, fmt.Errorf("apply WireGuard peer: %w", err)
	}
	if err := s.waitForPeerActive(syncCtx, peer.ID, accountID); err != nil {
		_ = s.communicator.PushPeerRemoved(context.Background(), serverID, peer)
		_ = s.postgres.DeletePeer(context.Background(), peer.ID, accountID)
		_ = s.redis.ReleaseIP(context.Background(), serverID, assignedIP)
		return nil, fmt.Errorf("wait for WireGuard peer acknowledgement: %w", err)
	}

	s.log.Info("peer created",
		"peer_id", peer.ID,
		"account_id", accountID,
		"device_id", deviceID,
		"server_id", srv.ID,
		"assigned_ip", assignedIP,
		"client_ip", clientIP,
		"server_endpoint", endpoint,
	)

	s.publishEvent("peer.created", map[string]interface{}{
		"peer_id":         peer.ID,
		"account_id":      accountID,
		"device_id":       deviceID,
		"server_id":       srv.ID,
		"assigned_ip":     assignedIP,
		"server_endpoint": endpoint,
	})

	return &PeerConfig{
		PeerID:            peer.ID,
		ServerID:          srv.ID,
		ServerHostname:    srv.Hostname,
		ServerPublicKey:   srv.PublicKey,
		ServerEndpoint:    endpoint,
		StealthEndpoint:   s.ClientStealthEndpoint(srv, clientIP),
		StealthAvailable:  s.StealthAvailable(),
		StealthPathPrefix: s.StealthPathPrefix(),
		AssignedIP:        assignedIP,
		DNSServer:         srv.DNSServer,
		PresharedKey:      psk,
		AllowedIPs:        []string{assignedIP},
		// The production tunnel currently provides IPv4 egress only. Advertising
		// the IPv6 default to managed clients makes IPv6 fail closed inside the
		// tunnel instead of bypassing it on dual-stack access networks.
		ClientAllowedIPs:       []string{"0.0.0.0/0", "::/0"},
		PersistentKeepaliveSec: 25,
		DeviceID:               deviceID,
	}, nil
}

func stripCIDR(ip string) string {
	for i := 0; i < len(ip); i++ {
		if ip[i] == '/' {
			return ip[:i]
		}
	}
	return ip
}

func (s *Service) waitForPeerActive(ctx context.Context, peerID, accountID string) error {
	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()
	var lastErr error
	for {
		peer, err := s.postgres.GetPeer(ctx, peerID, accountID)
		if err == nil {
			if peer.Status == "active" {
				return nil
			}
			if peer.Status == "removed" {
				return fmt.Errorf("peer was removed before acknowledgement")
			}
		} else {
			lastErr = err
		}
		select {
		case <-ctx.Done():
			if lastErr != nil {
				return lastErr
			}
			return ctx.Err()
		case <-ticker.C:
		}
	}
}

func (s *Service) DeletePeer(ctx context.Context, peerID, accountID string) error {
	peer, err := s.postgres.GetPeer(ctx, peerID, accountID)
	if err != nil {
		return fmt.Errorf("get peer for deletion: %w", err)
	}

	// Soft-delete does not fire FK CASCADE; remove forwards explicitly and notify the agent.
	forwards, err := s.postgres.ListPortForwardsByPeer(ctx, peerID)
	if err != nil {
		s.log.Warn("list port forwards before peer delete failed", "peer_id", peerID, "error", err)
	} else {
		for i := range forwards {
			pf := forwards[i]
			s.communicator.PushPortForwardRemove(peer.ServerID, &pf)
			if delErr := s.postgres.DeletePortForward(ctx, pf.ID, accountID); delErr != nil {
				s.log.Warn("delete port forward on peer delete failed",
					"forward_id", pf.ID, "peer_id", peerID, "error", delErr)
			}
		}
	}

	// Notify the agent before soft-deleting (same ordering as ExpirePeer) so a
	// successful DELETE does not leave a kernel peer until PEER_STALE_AFTER.
	// On push failure leave the peer active so stale GC can still expire it.
	pushCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	err = s.communicator.PushPeerRemoved(pushCtx, peer.ServerID, peer)
	cancel()
	if err != nil {
		return fmt.Errorf("remove peer from agent: %w", err)
	}

	if err := s.postgres.DeletePeer(ctx, peerID, accountID); err != nil {
		return fmt.Errorf("delete peer: %w", err)
	}

	_ = s.redis.ReleaseIP(ctx, peer.ServerID, peer.AssignedIP)

	s.log.Info("peer deleted", "peer_id", peerID, "account_id", accountID)

	s.publishEvent("peer.deleted", map[string]interface{}{
		"peer_id":    peerID,
		"account_id": accountID,
		"server_id":  peer.ServerID,
	})

	return nil
}

// ExpirePeer reconciles an abandoned WireGuard session. The agent notification
// is delivered before the database state is changed so kernel and database
// cannot silently diverge.
func (s *Service) ExpirePeer(ctx context.Context, serverID, peerID string) error {
	peer, err := s.postgres.GetPeerForServer(ctx, peerID, serverID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil
		}
		return fmt.Errorf("get stale peer: %w", err)
	}
	pushCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	err = s.communicator.PushPeerRemoved(pushCtx, serverID, peer)
	cancel()
	if err != nil {
		return fmt.Errorf("remove stale peer from agent: %w", err)
	}
	changed, err := s.postgres.MarkPeerRemovedForServer(ctx, peerID, serverID)
	if err != nil {
		return err
	}
	if changed {
		_ = s.redis.ReleaseIP(ctx, serverID, peer.AssignedIP)
		s.publishEvent("peer.expired", map[string]interface{}{
			"peer_id": peerID, "account_id": peer.AccountID, "server_id": serverID,
		})
	}
	return nil
}

func (s *Service) GetPeer(ctx context.Context, peerID, accountID string) (*model.Peer, *model.Server, error) {
	peer, err := s.postgres.GetPeer(ctx, peerID, accountID)
	if err != nil {
		return nil, nil, fmt.Errorf("get peer: %w", err)
	}

	srv, err := s.postgres.GetServer(ctx, peer.ServerID)
	if err != nil {
		s.log.Warn("could not get server for peer", "server_id", peer.ServerID, "error", err)
		return peer, nil, nil
	}

	return peer, srv, nil
}

func (s *Service) ListPeers(ctx context.Context, accountID string) ([]model.Peer, error) {
	peers, err := s.postgres.ListPeersByAccount(ctx, accountID)
	if err != nil {
		return nil, fmt.Errorf("list peers: %w", err)
	}
	return peers, nil
}

func (s *Service) ListPeersForServer(ctx context.Context, serverID string) ([]model.Peer, error) {
	return s.postgres.ListPeersByServer(ctx, serverID)
}

func (s *Service) MarkPeerActive(ctx context.Context, peerID string) error {
	return s.postgres.UpdatePeerStatus(ctx, peerID, "active")
}

func (s *Service) ListServers(ctx context.Context) ([]model.Server, error) {
	servers, err := s.postgres.ListOnlineServers(ctx)
	if err != nil {
		return nil, fmt.Errorf("list servers: %w", err)
	}
	return servers, nil
}

func (s *Service) CreatePortForward(ctx context.Context, accountID, tier, peerID, protocol string, externalPort, internalPort int) (*model.PortForward, error) {
	tier = s.resolveTier(accountID, tier)

	proto, err := entitlement.NormalizeProtocol(protocol)
	if err != nil {
		return nil, err
	}
	if err := entitlement.ValidateExternalPort(externalPort); err != nil {
		return nil, err
	}
	if internalPort == 0 {
		internalPort = externalPort
	}
	if err := entitlement.ValidateInternalPort(internalPort); err != nil {
		return nil, err
	}

	count, err := s.postgres.CountPortForwardsByAccount(ctx, accountID)
	if err != nil {
		return nil, fmt.Errorf("count port forwards: %w", err)
	}
	if err := entitlement.CheckCreatePortForward(tier, count); err != nil {
		return nil, err
	}

	peer, err := s.postgres.GetPeer(ctx, peerID, accountID)
	if err != nil {
		return nil, fmt.Errorf("peer not found: %w", err)
	}
	if peer.Status != "pending" && peer.Status != "active" {
		return nil, fmt.Errorf("peer must be pending or active")
	}

	taken, err := s.postgres.IsPortTaken(ctx, proto, externalPort)
	if err != nil {
		return nil, err
	}
	if taken {
		return nil, &entitlement.PlanError{
			Code: "external_port_taken",
			Message: fmt.Sprintf(
				"external_port %d/%s is already in use; try another port in %d-%d",
				externalPort, proto,
				entitlement.RecommendedExternalPortMin, entitlement.RecommendedExternalPortMax,
			),
		}
	}

	assignedIP := peer.AssignedIP
	if i := strings.IndexByte(assignedIP, '/'); i >= 0 {
		assignedIP = assignedIP[:i]
	}

	pf := &model.PortForward{
		AccountID:    accountID,
		PeerID:       peerID,
		Protocol:     proto,
		ExternalPort: externalPort,
		InternalPort: internalPort,
		Status:       "pending",
		ServerID:     peer.ServerID,
		AssignedIP:   assignedIP,
	}
	if err := s.postgres.CreatePortForward(ctx, pf); err != nil {
		return nil, fmt.Errorf("create port forward: %w", err)
	}

	if srv, srvErr := s.postgres.GetServer(ctx, peer.ServerID); srvErr == nil && srv != nil {
		pf.EgressEndpoint = srv.PublicIP
	}

	if s.communicator.PushPortForwardAdd(peer.ServerID, pf) {
		if err := s.postgres.UpdatePortForwardStatus(ctx, pf.ID, "active"); err != nil {
			s.log.Warn("mark port forward active failed", "forward_id", pf.ID, "error", err)
		} else {
			pf.Status = "active"
		}
	}

	s.log.Info("port forward created",
		"forward_id", pf.ID,
		"account_id", accountID,
		"peer_id", peerID,
		"protocol", proto,
		"external_port", externalPort,
		"internal_port", internalPort,
		"status", pf.Status,
	)
	s.publishEvent("port_forward.created", map[string]interface{}{
		"forward_id":    pf.ID,
		"account_id":    accountID,
		"peer_id":       peerID,
		"server_id":     peer.ServerID,
		"protocol":      proto,
		"external_port": externalPort,
		"internal_port": internalPort,
		"status":        pf.Status,
	})
	return pf, nil
}

func (s *Service) ListPortForwards(ctx context.Context, accountID string) ([]model.PortForward, error) {
	return s.postgres.ListPortForwardsByAccount(ctx, accountID)
}

func (s *Service) ListPortForwardsForServer(ctx context.Context, serverID string) ([]model.PortForward, error) {
	return s.postgres.ListPortForwardsByServer(ctx, serverID)
}

func (s *Service) DeletePortForward(ctx context.Context, id, accountID string) error {
	pf, err := s.postgres.GetPortForward(ctx, id, accountID)
	if err != nil {
		return fmt.Errorf("get port forward: %w", err)
	}

	if err := s.postgres.DeletePortForward(ctx, id, accountID); err != nil {
		return err
	}

	s.communicator.PushPortForwardRemove(pf.ServerID, pf)

	s.log.Info("port forward deleted", "forward_id", id, "account_id", accountID)
	s.publishEvent("port_forward.deleted", map[string]interface{}{
		"forward_id": id,
		"account_id": accountID,
		"peer_id":    pf.PeerID,
		"server_id":  pf.ServerID,
	})
	return nil
}

func (s *Service) allocateSubnet(ctx context.Context) (string, error) {
	counter, err := s.redis.Client().Incr(ctx, "wg:subnet_counter").Result()
	if err != nil {
		return "", fmt.Errorf("allocate subnet: %w", err)
	}
	// First server -> 10.0.0.0/24 to match typical single-node bootstrap.
	return fmt.Sprintf("10.%d.0.0/24", counter-1), nil
}

func (s *Service) publishEvent(subject string, data map[string]interface{}) {
	payload, err := json.Marshal(data)
	if err != nil {
		s.log.Warn("failed to marshal event", "subject", subject, "error", err)
		return
	}
	if err := s.natsConn.Publish(subject, payload); err != nil {
		s.log.Warn("failed to publish event", "subject", subject, "error", err)
	}
}
