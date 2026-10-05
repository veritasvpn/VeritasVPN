package repository

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/veritasvpn/services/wg-manager/internal/model"
)

const peerColumns = `id, account_id, server_id, device_id,
       COALESCE(device_name, ''), COALESCE(device_platform, ''),
       COALESCE(device_model, ''), COALESCE(device_os_version, ''),
       COALESCE(client_version, ''), pubkey, preshared_key,
       allowed_ips, assigned_ip, status, COALESCE(shield_preset, 'standard'),
       created_at, last_handshake_at, expires_at,
       COALESCE(shield_policy_set, false), COALESCE(shield_block_malicious, false),
       COALESCE(shield_block_ads, false), COALESCE(shield_block_adult, false),
       COALESCE(shield_block_trackers, false)`

func scanPeerArgs(peer *model.Peer) []any {
	return []any{
		&peer.ID, &peer.AccountID, &peer.ServerID, &peer.DeviceID,
		&peer.DeviceName, &peer.DevicePlatform, &peer.DeviceModel, &peer.DeviceOSVersion, &peer.ClientVersion,
		&peer.Pubkey, &peer.PresharedKey, &peer.AllowedIPs, &peer.AssignedIP,
		&peer.Status, &peer.ShieldPreset, &peer.CreatedAt, &peer.LastHandshakeAt, &peer.ExpiresAt,
		&peer.ShieldPolicySet, &peer.ShieldBlockMalicious, &peer.ShieldBlockAds, &peer.ShieldBlockAdult, &peer.ShieldBlockTrackers,
	}
}

type Postgres struct {
	pool *pgxpool.Pool
}

func NewPostgres(pool *pgxpool.Pool) *Postgres {
	return &Postgres{pool: pool}
}

// GetServerByHostname returns the server row including its agent token hash,
// which RegisterServer needs to authorize re-registration of an enrolled node.
func (p *Postgres) GetServerByHostname(ctx context.Context, hostname string) (*model.Server, error) {
	query := `SELECT id, hostname, region, city, country, public_ip, wg_port,
	           public_key, status, capacity, load_factor, wg_subnet, dns_server,
	           agent_token_hash, agent_token_issued_at,
	           created_at, updated_at FROM servers WHERE hostname = $1`

	srv := &model.Server{}
	var agentTokenHash *string
	err := p.pool.QueryRow(ctx, query, hostname).Scan(
		&srv.ID, &srv.Hostname, &srv.Region, &srv.City, &srv.Country,
		&srv.PublicIP, &srv.WGPort, &srv.PublicKey, &srv.Status,
		&srv.Capacity, &srv.LoadFactor, &srv.WGSubnet, &srv.DNSServer,
		&agentTokenHash, &srv.AgentTokenIssuedAt,
		&srv.CreatedAt, &srv.UpdatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("get server by hostname: %w", err)
	}
	if agentTokenHash != nil {
		srv.AgentTokenHash = *agentTokenHash
	}
	return srv, nil
}

func (p *Postgres) GetServerByPublicKey(ctx context.Context, publicKey string) (*model.Server, error) {
	query := `SELECT id, hostname, region, city, country, public_ip, wg_port,
	           public_key, status, capacity, load_factor, wg_subnet, dns_server,
	           created_at, updated_at FROM servers WHERE public_key = $1
	           ORDER BY updated_at DESC NULLS LAST, created_at DESC LIMIT 1`

	srv := &model.Server{}
	err := p.pool.QueryRow(ctx, query, publicKey).Scan(
		&srv.ID, &srv.Hostname, &srv.Region, &srv.City, &srv.Country,
		&srv.PublicIP, &srv.WGPort, &srv.PublicKey, &srv.Status,
		&srv.Capacity, &srv.LoadFactor, &srv.WGSubnet, &srv.DNSServer,
		&srv.CreatedAt, &srv.UpdatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("get server by public key: %w", err)
	}
	return srv, nil
}

func (p *Postgres) MarkDuplicateServersOffline(ctx context.Context, keepID, publicIP, publicKey string) error {
	_, err := p.pool.Exec(ctx, `
		UPDATE servers
		SET status = 'offline', updated_at = NOW()
		WHERE id <> $1
		  AND status = 'online'
		  AND (public_ip = $2 OR public_key = $3)`,
		keepID, publicIP, publicKey,
	)
	if err != nil {
		return fmt.Errorf("mark duplicate servers offline: %w", err)
	}
	return nil
}

func (p *Postgres) RegisterServer(ctx context.Context, srv *model.Server) error {
	query := `INSERT INTO servers (hostname, region, city, country, public_ip,
	           wg_port, public_key, status, capacity, wg_subnet, dns_server,
	           agent_token_hash, agent_token_issued_at)
	           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
	           ON CONFLICT (hostname) DO UPDATE SET
	               public_ip = EXCLUDED.public_ip, wg_port = EXCLUDED.wg_port,
	               public_key = EXCLUDED.public_key, status = EXCLUDED.status,
	               capacity = EXCLUDED.capacity,
	               region = EXCLUDED.region, city = EXCLUDED.city, country = EXCLUDED.country,
	               agent_token_hash = EXCLUDED.agent_token_hash,
	               agent_token_issued_at = EXCLUDED.agent_token_issued_at,
	               updated_at = NOW()
	           RETURNING id, wg_subnet, dns_server`

	return p.pool.QueryRow(ctx, query,
		srv.Hostname, srv.Region, srv.City, srv.Country,
		srv.PublicIP, srv.WGPort, srv.PublicKey, srv.Status,
		srv.Capacity, srv.WGSubnet, srv.DNSServer,
		nullIfEmpty(srv.AgentTokenHash), srv.AgentTokenIssuedAt,
	).Scan(&srv.ID, &srv.WGSubnet, &srv.DNSServer)
}

func (p *Postgres) UpdateServerIdentity(ctx context.Context, srv *model.Server) error {
	_, err := p.pool.Exec(ctx, `
		UPDATE servers SET
		  hostname = $2,
		  region = $3,
		  city = $4,
		  country = $5,
		  public_ip = $6,
		  wg_port = $7,
		  public_key = $8,
		  status = $9,
		  agent_token_hash = $10,
		  agent_token_issued_at = $11,
		  updated_at = NOW()
		WHERE id = $1`,
		srv.ID, srv.Hostname, srv.Region, srv.City, srv.Country,
		srv.PublicIP, srv.WGPort, srv.PublicKey, srv.Status,
		nullIfEmpty(srv.AgentTokenHash), srv.AgentTokenIssuedAt,
	)
	if err != nil {
		return fmt.Errorf("update server identity: %w", err)
	}
	return nil
}

func nullIfEmpty(s string) interface{} {
	if s == "" {
		return nil
	}
	return s
}

// GetServerAgentTokenHash returns the stored agent token hash for serverID.
func (p *Postgres) GetServerAgentTokenHash(ctx context.Context, serverID string) (string, error) {
	var hash *string
	err := p.pool.QueryRow(ctx,
		`SELECT agent_token_hash FROM servers WHERE id = $1`, serverID).Scan(&hash)
	if err != nil {
		return "", fmt.Errorf("get server agent token hash: %w", err)
	}
	if hash == nil {
		return "", nil
	}
	return *hash, nil
}

// GetAccountSubscriptionTier returns accounts.subscription_tier for entitlement checks.
func (p *Postgres) GetAccountSubscriptionTier(ctx context.Context, accountID string) (string, error) {
	var tier string
	err := p.pool.QueryRow(ctx,
		`SELECT subscription_tier FROM accounts WHERE id = $1 AND account_status != 'deleted'`,
		accountID).Scan(&tier)
	if err != nil {
		return "", fmt.Errorf("get account subscription tier: %w", err)
	}
	return tier, nil
}

func (p *Postgres) GetServer(ctx context.Context, id string) (*model.Server, error) {
	query := `SELECT id, hostname, region, city, country, public_ip, wg_port,
	           public_key, status, capacity, load_factor, wg_subnet, dns_server,
	           created_at, updated_at FROM servers WHERE id = $1`

	srv := &model.Server{}
	err := p.pool.QueryRow(ctx, query, id).Scan(
		&srv.ID, &srv.Hostname, &srv.Region, &srv.City, &srv.Country,
		&srv.PublicIP, &srv.WGPort, &srv.PublicKey, &srv.Status,
		&srv.Capacity, &srv.LoadFactor, &srv.WGSubnet, &srv.DNSServer,
		&srv.CreatedAt, &srv.UpdatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("get server: %w", err)
	}
	return srv, nil
}

func (p *Postgres) ListOnlineServers(ctx context.Context) ([]model.Server, error) {
	query := `SELECT id, hostname, region, city, country, public_ip, wg_port,
	           public_key, status, capacity, load_factor, wg_subnet, dns_server,
	           created_at, updated_at FROM servers WHERE status = 'online'
	           ORDER BY load_factor ASC`

	rows, err := p.pool.Query(ctx, query)
	if err != nil {
		return nil, fmt.Errorf("list servers: %w", err)
	}
	defer rows.Close()

	var servers []model.Server
	for rows.Next() {
		var s model.Server
		if err := rows.Scan(
			&s.ID, &s.Hostname, &s.Region, &s.City, &s.Country,
			&s.PublicIP, &s.WGPort, &s.PublicKey, &s.Status,
			&s.Capacity, &s.LoadFactor, &s.WGSubnet, &s.DNSServer,
			&s.CreatedAt, &s.UpdatedAt,
		); err != nil {
			return nil, fmt.Errorf("scan server: %w", err)
		}
		servers = append(servers, s)
	}
	return servers, rows.Err()
}

func (p *Postgres) UpdateServerStatus(ctx context.Context, id, status string) error {
	_, err := p.pool.Exec(ctx,
		`UPDATE servers SET status = $2, updated_at = NOW() WHERE id = $1`,
		id, status)
	return err
}

func (p *Postgres) UpdateServerLoad(ctx context.Context, id string, peerCount int32, loadFactor float64) error {
	_, err := p.pool.Exec(ctx,
		`UPDATE servers SET load_factor = $2, updated_at = NOW() WHERE id = $1`,
		id, loadFactor)
	_ = peerCount
	return err
}

func (p *Postgres) IsActiveIPAssigned(ctx context.Context, serverID, assignedIP string) (bool, error) {
	var assigned bool
	err := p.pool.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM peers
			WHERE server_id = $1 AND assigned_ip = $2
			  AND status IN ('pending', 'active')
		)`, serverID, assignedIP).Scan(&assigned)
	if err != nil {
		return false, fmt.Errorf("check assigned ip: %w", err)
	}
	return assigned, nil
}

// LatestPeerByAccountDevice returns the newest peer row for a device,
// including removed rows. Reconnect can delete the active peer before
// creating the next one; display metadata still has to come from that row.
func (p *Postgres) LatestPeerByAccountDevice(ctx context.Context, accountID, deviceID string) (*model.Peer, error) {
	query := `SELECT ` + peerColumns + ` FROM peers
	           WHERE account_id = $1 AND device_id = $2
	           ORDER BY created_at DESC
	           LIMIT 1`

	peer := &model.Peer{}
	err := p.pool.QueryRow(ctx, query, accountID, deviceID).Scan(scanPeerArgs(peer)...)
	if err != nil {
		return nil, fmt.Errorf("latest peer by account device: %w", err)
	}
	return peer, nil
}

func (p *Postgres) GetActivePeerByAccountDevice(ctx context.Context, accountID, deviceID string) (*model.Peer, error) {
	query := `SELECT ` + peerColumns + ` FROM peers
	           WHERE account_id = $1 AND device_id = $2
	             AND status IN ('pending', 'active')
	           LIMIT 1`

	peer := &model.Peer{}
	err := p.pool.QueryRow(ctx, query, accountID, deviceID).Scan(scanPeerArgs(peer)...)
	if err != nil {
		return nil, fmt.Errorf("get active peer by account device: %w", err)
	}
	return peer, nil
}

func (p *Postgres) CreatePeer(ctx context.Context, peer *model.Peer) error {
	query := `INSERT INTO peers (account_id, server_id, device_id, device_name, device_platform,
	           device_model, device_os_version, client_version, pubkey, preshared_key,
	           allowed_ips, assigned_ip, status, shield_preset, expires_at,
	           shield_policy_set, shield_block_malicious, shield_block_ads, shield_block_adult,
	           shield_block_trackers)
	           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20)
	           RETURNING id, created_at`

	return p.pool.QueryRow(ctx, query,
		peer.AccountID, peer.ServerID, peer.DeviceID, peer.DeviceName, peer.DevicePlatform,
		peer.DeviceModel, peer.DeviceOSVersion, peer.ClientVersion, peer.Pubkey, peer.PresharedKey,
		peer.AllowedIPs, peer.AssignedIP, peer.Status, peer.ShieldPreset, peer.ExpiresAt,
		peer.ShieldPolicySet, peer.ShieldBlockMalicious, peer.ShieldBlockAds, peer.ShieldBlockAdult,
		peer.ShieldBlockTrackers,
	).Scan(&peer.ID, &peer.CreatedAt)
}

// UpdatePeerIdentity replaces the WireGuard identity/server assignment for an
// existing device peer row and resets status to pending for agent apply.
func (p *Postgres) UpdatePeerIdentity(ctx context.Context, peer *model.Peer) error {
	query := `UPDATE peers SET
	               server_id = $2,
	               device_name = $3,
	               device_platform = $4,
	               device_model = $5,
	               device_os_version = $6,
	               client_version = $7,
	               pubkey = $8,
	               preshared_key = $9,
	               allowed_ips = $10,
	               assigned_ip = $11,
	               status = 'pending',
		       expires_at = $12,
		       last_handshake_at = NULL,
		       shield_preset = $14,
		       shield_policy_set = $15,
		       shield_block_malicious = $16,
		       shield_block_ads = $17,
		       shield_block_adult = $18,
		       shield_block_trackers = $19
	           WHERE id = $1 AND account_id = $13 AND status IN ('pending', 'active')
	           RETURNING created_at`
	return p.pool.QueryRow(ctx, query,
		peer.ID, peer.ServerID, peer.DeviceName, peer.DevicePlatform, peer.DeviceModel, peer.DeviceOSVersion, peer.ClientVersion,
		peer.Pubkey, peer.PresharedKey, peer.AllowedIPs, peer.AssignedIP, peer.ExpiresAt, peer.AccountID,
		peer.ShieldPreset, peer.ShieldPolicySet, peer.ShieldBlockMalicious, peer.ShieldBlockAds, peer.ShieldBlockAdult,
		peer.ShieldBlockTrackers,
	).Scan(&peer.CreatedAt)
}

// UpdatePeerShieldPreset stores a legacy preset and clears explicit toggles
// so the agent expands that preset again.
func (p *Postgres) UpdatePeerShieldPreset(ctx context.Context, peerID, accountID, preset string) (*model.Peer, error) {
	query := `UPDATE peers SET shield_preset = $3, shield_policy_set = false
	           WHERE id = $1 AND account_id = $2 AND status IN ('pending', 'active')
	           RETURNING ` + peerColumns
	peer := &model.Peer{}
	err := p.pool.QueryRow(ctx, query, peerID, accountID, preset).Scan(scanPeerArgs(peer)...)
	if err != nil {
		return nil, fmt.Errorf("update peer shield preset: %w", err)
	}
	return peer, nil
}

// UpdatePeerShieldPolicy stores the four Premium toggles and a preset alias
// for agents that have not learned explicit flags yet.
func (p *Postgres) UpdatePeerShieldPolicy(ctx context.Context, peerID, accountID, preset string, malicious, ads, adult, trackers bool) (*model.Peer, error) {
	query := `UPDATE peers SET shield_preset = $3, shield_policy_set = true,
	             shield_block_malicious = $4, shield_block_ads = $5, shield_block_adult = $6,
	             shield_block_trackers = $7
	           WHERE id = $1 AND account_id = $2 AND status IN ('pending', 'active')
	           RETURNING ` + peerColumns
	peer := &model.Peer{}
	err := p.pool.QueryRow(ctx, query, peerID, accountID, preset, malicious, ads, adult, trackers).Scan(scanPeerArgs(peer)...)
	if err != nil {
		return nil, fmt.Errorf("update peer shield policy: %w", err)
	}
	return peer, nil
}

func (p *Postgres) GetPeer(ctx context.Context, peerID, accountID string) (*model.Peer, error) {
	query := `SELECT ` + peerColumns + ` FROM peers WHERE id = $1 AND account_id = $2 AND status != 'removed'`

	peer := &model.Peer{}
	err := p.pool.QueryRow(ctx, query, peerID, accountID).Scan(scanPeerArgs(peer)...)
	if err != nil {
		return nil, fmt.Errorf("get peer: %w", err)
	}
	return peer, nil
}

func (p *Postgres) ListPeersByAccount(ctx context.Context, accountID string) ([]model.Peer, error) {
	query := `SELECT ` + peerColumns + ` FROM peers WHERE account_id = $1 AND status != 'removed'
	           ORDER BY created_at DESC`

	rows, err := p.pool.Query(ctx, query, accountID)
	if err != nil {
		return nil, fmt.Errorf("list peers: %w", err)
	}
	defer rows.Close()

	var peers []model.Peer
	for rows.Next() {
		var peer model.Peer
		if err := rows.Scan(scanPeerArgs(&peer)...); err != nil {
			return nil, fmt.Errorf("scan peer: %w", err)
		}
		peers = append(peers, peer)
	}
	return peers, rows.Err()
}

func (p *Postgres) GetPeerForServer(ctx context.Context, peerID, serverID string) (*model.Peer, error) {
	query := `SELECT ` + peerColumns + ` FROM peers WHERE id = $1 AND server_id = $2
	             AND status IN ('pending', 'active')`
	peer := &model.Peer{}
	err := p.pool.QueryRow(ctx, query, peerID, serverID).Scan(scanPeerArgs(peer)...)
	if err != nil {
		return nil, fmt.Errorf("get peer for server: %w", err)
	}
	return peer, nil
}

func (p *Postgres) MarkPeerRemovedForServer(ctx context.Context, peerID, serverID string) (bool, error) {
	result, err := p.pool.Exec(ctx,
		`UPDATE peers SET status = 'removed' WHERE id = $1 AND server_id = $2
		  AND status IN ('pending', 'active')`, peerID, serverID)
	if err != nil {
		return false, fmt.Errorf("mark peer removed: %w", err)
	}
	return result.RowsAffected() > 0, nil
}

func (p *Postgres) ListPeersByServer(ctx context.Context, serverID string) ([]model.Peer, error) {
	query := `SELECT ` + peerColumns + ` FROM peers WHERE server_id = $1 AND status IN ('pending', 'active')
	           ORDER BY created_at ASC`

	rows, err := p.pool.Query(ctx, query, serverID)
	if err != nil {
		return nil, fmt.Errorf("list server peers: %w", err)
	}
	defer rows.Close()

	var peers []model.Peer
	for rows.Next() {
		var peer model.Peer
		if err := rows.Scan(scanPeerArgs(&peer)...); err != nil {
			return nil, fmt.Errorf("scan peer: %w", err)
		}
		peers = append(peers, peer)
	}
	return peers, rows.Err()
}

// UpdatePeerMetadata stores bounded, display-only client metadata. It never
// changes keys, routes, entitlement, or any authentication decision.
func (p *Postgres) UpdatePeerMetadata(ctx context.Context, peerID, accountID, name, platform, deviceModel, osVersion, clientVersion string) (*model.Peer, error) {
	query := `UPDATE peers SET device_name = $3, device_platform = $4,
	             device_model = $5, device_os_version = $6, client_version = $7
	           WHERE id = $1 AND account_id = $2 AND status IN ('pending', 'active')
	           RETURNING ` + peerColumns
	peer := &model.Peer{}
	if err := p.pool.QueryRow(ctx, query, peerID, accountID, name, platform, deviceModel, osVersion, clientVersion).Scan(scanPeerArgs(peer)...); err != nil {
		return nil, fmt.Errorf("update peer metadata: %w", err)
	}
	return peer, nil
}

// UpdatePeerHandshakes records authenticated agent telemetry. A zero value is
// deliberately ignored: it must never overwrite a previously observed
// handshake time.
func (p *Postgres) UpdatePeerHandshakes(ctx context.Context, serverID string, handshakes map[string]time.Time) error {
	if len(handshakes) == 0 {
		return nil
	}
	batch := &pgx.Batch{}
	queued := 0
	for peerID, at := range handshakes {
		if peerID == "" || at.IsZero() {
			continue
		}
		batch.Queue(`UPDATE peers SET last_handshake_at = GREATEST(COALESCE(last_handshake_at, to_timestamp(0)), $3)
			WHERE id = $1 AND server_id = $2 AND status IN ('pending', 'active')`, peerID, serverID, at.UTC())
		queued++
	}
	if queued == 0 {
		return nil
	}
	results := p.pool.SendBatch(ctx, batch)
	defer results.Close()
	for range queued {
		if _, err := results.Exec(); err != nil {
			return fmt.Errorf("update peer handshake: %w", err)
		}
	}
	return nil
}

func (p *Postgres) UpdatePeerStatus(ctx context.Context, peerID, status string) error {
	_, err := p.pool.Exec(ctx,
		`UPDATE peers SET status = $2 WHERE id = $1`,
		peerID, status)
	return err
}

// UpdatePeerStatusForServer marks a peer active only when it belongs to serverID.
func (p *Postgres) UpdatePeerStatusForServer(ctx context.Context, peerID, serverID, status string) error {
	result, err := p.pool.Exec(ctx,
		`UPDATE peers SET status = $3 WHERE id = $1 AND server_id = $2
		  AND status IN ('pending', 'active')`,
		peerID, serverID, status)
	if err != nil {
		return fmt.Errorf("update peer status for server: %w", err)
	}
	if result.RowsAffected() == 0 {
		return fmt.Errorf("peer not found on server")
	}
	return nil
}

func (p *Postgres) DeletePeer(ctx context.Context, peerID, accountID string) error {
	_, err := p.pool.Exec(ctx,
		`UPDATE peers SET status = 'removed' WHERE id = $1 AND account_id = $2`,
		peerID, accountID)
	return err
}
