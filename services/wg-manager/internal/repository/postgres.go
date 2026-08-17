package repository

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/veritasvpn/services/wg-manager/internal/model"
)

type Postgres struct {
	pool *pgxpool.Pool
}

func NewPostgres(pool *pgxpool.Pool) *Postgres {
	return &Postgres{pool: pool}
}

func (p *Postgres) GetServerByHostname(ctx context.Context, hostname string) (*model.Server, error) {
	query := `SELECT id, hostname, region, city, country, public_ip, wg_port,
	           public_key, status, capacity, load_factor, wg_subnet, dns_server,
	           created_at, updated_at FROM servers WHERE hostname = $1`

	srv := &model.Server{}
	err := p.pool.QueryRow(ctx, query, hostname).Scan(
		&srv.ID, &srv.Hostname, &srv.Region, &srv.City, &srv.Country,
		&srv.PublicIP, &srv.WGPort, &srv.PublicKey, &srv.Status,
		&srv.Capacity, &srv.LoadFactor, &srv.WGSubnet, &srv.DNSServer,
		&srv.CreatedAt, &srv.UpdatedAt,
	)
	if err != nil {
		return nil, fmt.Errorf("get server by hostname: %w", err)
	}
	return srv, nil
}

func (p *Postgres) RegisterServer(ctx context.Context, srv *model.Server) error {
	query := `INSERT INTO servers (hostname, region, city, country, public_ip,
	           wg_port, public_key, status, capacity, wg_subnet, dns_server)
	           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
	           ON CONFLICT (hostname) DO UPDATE SET
	               public_ip = EXCLUDED.public_ip, wg_port = EXCLUDED.wg_port,
	               public_key = EXCLUDED.public_key, status = EXCLUDED.status,
	               capacity = EXCLUDED.capacity,
	               region = EXCLUDED.region, city = EXCLUDED.city, country = EXCLUDED.country,
	               updated_at = NOW()
	           RETURNING id, wg_subnet, dns_server`

	return p.pool.QueryRow(ctx, query,
		srv.Hostname, srv.Region, srv.City, srv.Country,
		srv.PublicIP, srv.WGPort, srv.PublicKey, srv.Status,
		srv.Capacity, srv.WGSubnet, srv.DNSServer,
	).Scan(&srv.ID, &srv.WGSubnet, &srv.DNSServer)
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

func (p *Postgres) CreatePeer(ctx context.Context, peer *model.Peer) error {
	query := `INSERT INTO peers (account_id, server_id, pubkey, preshared_key,
	           allowed_ips, assigned_ip, status, expires_at)
	           VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
	           ON CONFLICT (account_id, server_id) DO UPDATE SET
	               pubkey = EXCLUDED.pubkey,
	               preshared_key = EXCLUDED.preshared_key,
	               allowed_ips = EXCLUDED.allowed_ips,
	               assigned_ip = EXCLUDED.assigned_ip,
	               status = 'pending',
	               expires_at = EXCLUDED.expires_at
	           RETURNING id, created_at`

	return p.pool.QueryRow(ctx, query,
		peer.AccountID, peer.ServerID, peer.Pubkey, peer.PresharedKey,
		peer.AllowedIPs, peer.AssignedIP, peer.Status, peer.ExpiresAt,
	).Scan(&peer.ID, &peer.CreatedAt)
}

func (p *Postgres) GetPeer(ctx context.Context, peerID, accountID string) (*model.Peer, error) {
	query := `SELECT id, account_id, server_id, pubkey, preshared_key,
	           allowed_ips, assigned_ip, status, created_at, expires_at
	           FROM peers WHERE id = $1 AND account_id = $2 AND status != 'removed'`

	peer := &model.Peer{}
	err := p.pool.QueryRow(ctx, query, peerID, accountID).Scan(
		&peer.ID, &peer.AccountID, &peer.ServerID, &peer.Pubkey,
		&peer.PresharedKey, &peer.AllowedIPs, &peer.AssignedIP,
		&peer.Status, &peer.CreatedAt, &peer.ExpiresAt,
	)
	if err != nil {
		return nil, fmt.Errorf("get peer: %w", err)
	}
	return peer, nil
}

func (p *Postgres) ListPeersByAccount(ctx context.Context, accountID string) ([]model.Peer, error) {
	query := `SELECT id, account_id, server_id, pubkey, preshared_key,
	           allowed_ips, assigned_ip, status, created_at, expires_at
	           FROM peers WHERE account_id = $1 AND status != 'removed'
	           ORDER BY created_at DESC`

	rows, err := p.pool.Query(ctx, query, accountID)
	if err != nil {
		return nil, fmt.Errorf("list peers: %w", err)
	}
	defer rows.Close()

	var peers []model.Peer
	for rows.Next() {
		var p model.Peer
		if err := rows.Scan(
			&p.ID, &p.AccountID, &p.ServerID, &p.Pubkey, &p.PresharedKey,
			&p.AllowedIPs, &p.AssignedIP, &p.Status, &p.CreatedAt, &p.ExpiresAt,
		); err != nil {
			return nil, fmt.Errorf("scan peer: %w", err)
		}
		peers = append(peers, p)
	}
	return peers, rows.Err()
}

func (p *Postgres) GetPeerForServer(ctx context.Context, peerID, serverID string) (*model.Peer, error) {
	query := `SELECT id, account_id, server_id, pubkey, preshared_key,
	           allowed_ips, assigned_ip, status, created_at, expires_at
	           FROM peers WHERE id = $1 AND server_id = $2
	             AND status IN ('pending', 'active')`
	peer := &model.Peer{}
	err := p.pool.QueryRow(ctx, query, peerID, serverID).Scan(
		&peer.ID, &peer.AccountID, &peer.ServerID, &peer.Pubkey,
		&peer.PresharedKey, &peer.AllowedIPs, &peer.AssignedIP,
		&peer.Status, &peer.CreatedAt, &peer.ExpiresAt,
	)
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
	query := `SELECT id, account_id, server_id, pubkey, preshared_key,
	           allowed_ips, assigned_ip, status, created_at, expires_at
	           FROM peers WHERE server_id = $1 AND status IN ('pending', 'active')
	           ORDER BY created_at ASC`

	rows, err := p.pool.Query(ctx, query, serverID)
	if err != nil {
		return nil, fmt.Errorf("list server peers: %w", err)
	}
	defer rows.Close()

	var peers []model.Peer
	for rows.Next() {
		var p model.Peer
		if err := rows.Scan(
			&p.ID, &p.AccountID, &p.ServerID, &p.Pubkey, &p.PresharedKey,
			&p.AllowedIPs, &p.AssignedIP, &p.Status, &p.CreatedAt, &p.ExpiresAt,
		); err != nil {
			return nil, fmt.Errorf("scan peer: %w", err)
		}
		peers = append(peers, p)
	}
	return peers, rows.Err()
}

func (p *Postgres) UpdatePeerStatus(ctx context.Context, peerID, status string) error {
	_, err := p.pool.Exec(ctx,
		`UPDATE peers SET status = $2 WHERE id = $1`,
		peerID, status)
	return err
}

func (p *Postgres) DeletePeer(ctx context.Context, peerID, accountID string) error {
	_, err := p.pool.Exec(ctx,
		`UPDATE peers SET status = 'removed' WHERE id = $1 AND account_id = $2`,
		peerID, accountID)
	return err
}
