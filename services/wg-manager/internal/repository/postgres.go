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
		  updated_at = NOW()
		WHERE id = $1`,
		srv.ID, srv.Hostname, srv.Region, srv.City, srv.Country,
		srv.PublicIP, srv.WGPort, srv.PublicKey, srv.Status,
	)
	if err != nil {
		return fmt.Errorf("update server identity: %w", err)
	}
	return nil
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

func stripCIDR(ip string) string {
	for i := 0; i < len(ip); i++ {
		if ip[i] == '/' {
			return ip[:i]
		}
	}
	return ip
}

func (p *Postgres) CreatePortForward(ctx context.Context, pf *model.PortForward) error {
	query := `INSERT INTO port_forwards (account_id, peer_id, protocol, external_port, internal_port, status)
	          VALUES ($1, $2, $3, $4, $5, $6)
	          RETURNING id, created_at`
	return p.pool.QueryRow(ctx, query,
		pf.AccountID, pf.PeerID, pf.Protocol, pf.ExternalPort, pf.InternalPort, pf.Status,
	).Scan(&pf.ID, &pf.CreatedAt)
}

func (p *Postgres) GetPortForward(ctx context.Context, id, accountID string) (*model.PortForward, error) {
	query := `SELECT pf.id, pf.account_id, pf.peer_id, pf.protocol, pf.external_port,
	                 pf.internal_port, pf.status, pf.created_at,
	                 peers.server_id, peers.assigned_ip, servers.public_ip
	          FROM port_forwards pf
	          JOIN peers ON peers.id = pf.peer_id
	          JOIN servers ON servers.id = peers.server_id
	          WHERE pf.id = $1 AND pf.account_id = $2 AND pf.status != 'removed'`
	pf := &model.PortForward{}
	var assigned, egress string
	err := p.pool.QueryRow(ctx, query, id, accountID).Scan(
		&pf.ID, &pf.AccountID, &pf.PeerID, &pf.Protocol, &pf.ExternalPort,
		&pf.InternalPort, &pf.Status, &pf.CreatedAt,
		&pf.ServerID, &assigned, &egress,
	)
	if err != nil {
		return nil, fmt.Errorf("get port forward: %w", err)
	}
	pf.AssignedIP = stripCIDR(assigned)
	pf.EgressEndpoint = egress
	return pf, nil
}

func (p *Postgres) ListPortForwardsByAccount(ctx context.Context, accountID string) ([]model.PortForward, error) {
	query := `SELECT pf.id, pf.account_id, pf.peer_id, pf.protocol, pf.external_port,
	                 pf.internal_port, pf.status, pf.created_at,
	                 peers.server_id, peers.assigned_ip, servers.public_ip
	          FROM port_forwards pf
	          JOIN peers ON peers.id = pf.peer_id
	          JOIN servers ON servers.id = peers.server_id
	          WHERE pf.account_id = $1 AND pf.status != 'removed'
	          ORDER BY pf.created_at DESC`
	rows, err := p.pool.Query(ctx, query, accountID)
	if err != nil {
		return nil, fmt.Errorf("list port forwards: %w", err)
	}
	defer rows.Close()

	var out []model.PortForward
	for rows.Next() {
		var pf model.PortForward
		var assigned, egress string
		if err := rows.Scan(
			&pf.ID, &pf.AccountID, &pf.PeerID, &pf.Protocol, &pf.ExternalPort,
			&pf.InternalPort, &pf.Status, &pf.CreatedAt,
			&pf.ServerID, &assigned, &egress,
		); err != nil {
			return nil, fmt.Errorf("scan port forward: %w", err)
		}
		pf.AssignedIP = stripCIDR(assigned)
		pf.EgressEndpoint = egress
		out = append(out, pf)
	}
	return out, rows.Err()
}

// ListPortForwardsByServer returns pending/active forwards for a VPN node
// (used for SSE catch-up). AssignedIP is stripped of a /32 suffix for nft.
func (p *Postgres) ListPortForwardsByServer(ctx context.Context, serverID string) ([]model.PortForward, error) {
	query := `SELECT pf.id, pf.account_id, pf.peer_id, pf.protocol, pf.external_port,
	                 pf.internal_port, pf.status, pf.created_at,
	                 peers.server_id, peers.assigned_ip
	          FROM port_forwards pf
	          JOIN peers ON peers.id = pf.peer_id
	          WHERE peers.server_id = $1
	            AND pf.status IN ('pending', 'active')
	            AND peers.status IN ('pending', 'active')
	          ORDER BY pf.created_at ASC`
	rows, err := p.pool.Query(ctx, query, serverID)
	if err != nil {
		return nil, fmt.Errorf("list server port forwards: %w", err)
	}
	defer rows.Close()

	var out []model.PortForward
	for rows.Next() {
		var pf model.PortForward
		var assigned string
		if err := rows.Scan(
			&pf.ID, &pf.AccountID, &pf.PeerID, &pf.Protocol, &pf.ExternalPort,
			&pf.InternalPort, &pf.Status, &pf.CreatedAt,
			&pf.ServerID, &assigned,
		); err != nil {
			return nil, fmt.Errorf("scan server port forward: %w", err)
		}
		pf.AssignedIP = stripCIDR(assigned)
		out = append(out, pf)
	}
	return out, rows.Err()
}

func (p *Postgres) ListPortForwardsByPeer(ctx context.Context, peerID string) ([]model.PortForward, error) {
	query := `SELECT pf.id, pf.account_id, pf.peer_id, pf.protocol, pf.external_port,
	                 pf.internal_port, pf.status, pf.created_at,
	                 peers.server_id, peers.assigned_ip
	          FROM port_forwards pf
	          JOIN peers ON peers.id = pf.peer_id
	          WHERE pf.peer_id = $1 AND pf.status IN ('pending', 'active')`
	rows, err := p.pool.Query(ctx, query, peerID)
	if err != nil {
		return nil, fmt.Errorf("list peer port forwards: %w", err)
	}
	defer rows.Close()

	var out []model.PortForward
	for rows.Next() {
		var pf model.PortForward
		var assigned string
		if err := rows.Scan(
			&pf.ID, &pf.AccountID, &pf.PeerID, &pf.Protocol, &pf.ExternalPort,
			&pf.InternalPort, &pf.Status, &pf.CreatedAt,
			&pf.ServerID, &assigned,
		); err != nil {
			return nil, fmt.Errorf("scan peer port forward: %w", err)
		}
		pf.AssignedIP = stripCIDR(assigned)
		out = append(out, pf)
	}
	return out, rows.Err()
}

// DeletePortForward hard-deletes a forward owned by the account.
func (p *Postgres) DeletePortForward(ctx context.Context, id, accountID string) error {
	result, err := p.pool.Exec(ctx,
		`DELETE FROM port_forwards WHERE id = $1 AND account_id = $2`,
		id, accountID)
	if err != nil {
		return fmt.Errorf("delete port forward: %w", err)
	}
	if result.RowsAffected() == 0 {
		return fmt.Errorf("port forward not found")
	}
	return nil
}

func (p *Postgres) UpdatePortForwardStatus(ctx context.Context, id, status string) error {
	_, err := p.pool.Exec(ctx,
		`UPDATE port_forwards SET status = $2 WHERE id = $1`,
		id, status)
	return err
}

func (p *Postgres) CountPortForwardsByAccount(ctx context.Context, accountID string) (int, error) {
	var n int
	err := p.pool.QueryRow(ctx, `
		SELECT COUNT(*) FROM port_forwards
		WHERE account_id = $1 AND status IN ('pending', 'active')`, accountID).Scan(&n)
	if err != nil {
		return 0, fmt.Errorf("count port forwards: %w", err)
	}
	return n, nil
}

func (p *Postgres) IsPortTaken(ctx context.Context, protocol string, externalPort int) (bool, error) {
	var taken bool
	err := p.pool.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM port_forwards
			WHERE protocol = $1 AND external_port = $2
			  AND status IN ('pending', 'active')
		)`, protocol, externalPort).Scan(&taken)
	if err != nil {
		return false, fmt.Errorf("check port taken: %w", err)
	}
	return taken, nil
}
