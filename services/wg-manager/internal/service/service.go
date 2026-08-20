package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
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
	PeerID           string
	ServerID         string
	ServerHostname   string
	ServerPublicKey  string
	ServerEndpoint   string
	AssignedIP       string
	DNSServer        string
	PresharedKey     string
	AllowedIPs             []string // server-side peer AllowedIPs (client /32)
	ClientAllowedIPs       []string // client tunnel AllowedIPs (full tunnel)
	PersistentKeepaliveSec int
}

type Service struct {
	postgres     *repository.Postgres
	redis        *repository.Redis
	scheduler    *scheduler.Scheduler
	communicator *communicator.Communicator
	natsConn     *nats.Conn
	authToken    string
	freeRegions  []string
	tierCache    *entitlement.TierCache
	log          *logging.Logger
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
	if err == nil && existing != nil {
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
		if err := s.postgres.RegisterServer(ctx, existing); err != nil {
			return nil, fmt.Errorf("update server: %w", err)
		}
		s.log.Info("server re-registered",
			"server_id", existing.ID,
			"hostname", hostname,
			"subnet", existing.WGSubnet,
		)
		return existing, nil
	}
	if err != nil && !strings.Contains(err.Error(), "no rows") {
		return nil, fmt.Errorf("lookup server: %w", err)
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

func (s *Service) HandleHeartbeat(ctx context.Context, serverID string, peerCount int32, loadFactor float64, rxBytes, txBytes int64) error {
	if err := s.postgres.UpdateServerLoad(ctx, serverID, peerCount, loadFactor); err != nil {
		return fmt.Errorf("update server load: %w", err)
	}

	if err := s.postgres.UpdateServerStatus(ctx, serverID, "online"); err != nil {
		s.log.Warn("failed to set server status to online", "server_id", serverID, "error", err)
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

func (s *Service) CreatePeer(ctx context.Context, accountID, tier, publicKey, preferredRegion string) (*PeerConfig, error) {
	tier = s.resolveTier(accountID, tier)

	existing, err := s.postgres.ListPeersByAccount(ctx, accountID)
	if err != nil {
		return nil, fmt.Errorf("list peers for entitlement: %w", err)
	}
	if err := entitlement.CheckCreatePeer(tier, len(existing), preferredRegion, s.freeRegions); err != nil {
		return nil, err
	}

	srv, err := s.scheduler.SelectServer(ctx, preferredRegion)
	if err != nil {
		return nil, err
	}
	if err := entitlement.CheckSelectedRegion(tier, srv.Region, s.freeRegions); err != nil {
		return nil, err
	}

	// A reconnect for the same account/server replaces the previous WireGuard
	// identity. Preserve it long enough to remove it from the agent after the
	// database upsert, otherwise wg0 accumulates stale public keys and /32s.
	var replacedPeer *model.Peer
	for i := range existing {
		if existing[i].ServerID == srv.ID {
			old := existing[i]
			replacedPeer = &old
			break
		}
	}

	// Redis is an allocation accelerator, not the source of truth. Its bitmap
	// can be lost, so always reconcile each candidate with PostgreSQL. Used
	// candidates deliberately remain marked in Redis, rebuilding the bitmap as
	// allocations are attempted after a cache reset.
	var assignedIP string
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
		if !used {
			assignedIP = candidate
			break
		}
	}
	if assignedIP == "" {
		return nil, fmt.Errorf("no available IPs in subnet %s", srv.WGSubnet)
	}

	psk, err := generatePSK()
	if err != nil {
		_ = s.redis.ReleaseIP(ctx, srv.ID, assignedIP)
		return nil, err
	}

	peer := &model.Peer{
		AccountID:    accountID,
		ServerID:     srv.ID,
		Pubkey:       publicKey,
		PresharedKey: &psk,
		AllowedIPs:   []string{assignedIP},
		AssignedIP:   assignedIP,
		Status:       "pending",
		CreatedAt:    time.Now(),
	}

	if err := s.postgres.CreatePeer(ctx, peer); err != nil {
		_ = s.redis.ReleaseIP(ctx, srv.ID, assignedIP)
		return nil, fmt.Errorf("create peer: %w", err)
	}

	endpoint := fmt.Sprintf("%s:%d", srv.PublicIP, srv.WGPort)
	serverID := srv.ID

	// Apply the update before returning the client configuration. Returning
	// before the agent has the peer creates a handshake race: the client can
	// bring up WireGuard while the server still has no matching public key.
	// This synchronous acknowledgement also makes rapid reconnects deterministic.
	syncCtx, syncCancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer syncCancel()
	if replacedPeer != nil && replacedPeer.Pubkey != peer.Pubkey {
		if err := s.communicator.PushPeerRemoved(syncCtx, serverID, replacedPeer); err != nil {
			_ = s.postgres.DeletePeer(context.Background(), peer.ID, accountID)
			_ = s.redis.ReleaseIP(context.Background(), serverID, assignedIP)
			return nil, fmt.Errorf("remove existing WireGuard peer: %w", err)
		}
		if replacedPeer.AssignedIP != assignedIP {
			_ = s.redis.ReleaseIP(syncCtx, serverID, replacedPeer.AssignedIP)
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
		"server_id", srv.ID,
		"assigned_ip", assignedIP,
	)

	s.publishEvent("peer.created", map[string]interface{}{
		"peer_id":         peer.ID,
		"account_id":      accountID,
		"server_id":       srv.ID,
		"assigned_ip":     assignedIP,
		"server_endpoint": endpoint,
	})

	return &PeerConfig{
		PeerID:           peer.ID,
		ServerID:         srv.ID,
		ServerHostname:   srv.Hostname,
		ServerPublicKey:  srv.PublicKey,
		ServerEndpoint:   endpoint,
		AssignedIP:       assignedIP,
		DNSServer:        srv.DNSServer,
		PresharedKey:     psk,
		AllowedIPs:             []string{assignedIP},
		ClientAllowedIPs:       []string{"0.0.0.0/0"},
		PersistentKeepaliveSec: 25,
	}, nil
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

	if err := s.postgres.DeletePeer(ctx, peerID, accountID); err != nil {
		return fmt.Errorf("delete peer: %w", err)
	}

	_ = s.redis.ReleaseIP(ctx, peer.ServerID, peer.AssignedIP)

	go func() {
		bgCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		if err := s.communicator.PushPeerRemoved(bgCtx, peer.ServerID, peer); err != nil {
			s.log.Warn("agent notification failed for deleted peer",
				"peer_id", peerID,
				"server_id", peer.ServerID,
				"error", err.Error(),
			)
		}
	}()

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
