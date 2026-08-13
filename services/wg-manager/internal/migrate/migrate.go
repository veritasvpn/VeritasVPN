package migrate

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
)

const schema = `
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS servers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hostname    TEXT NOT NULL UNIQUE,
    region      TEXT NOT NULL,
    city        TEXT NOT NULL,
    country     TEXT NOT NULL,
    public_ip   TEXT NOT NULL,
    wg_port     INTEGER NOT NULL DEFAULT 51820,
    public_key  TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'offline' CHECK (status IN ('offline', 'online', 'maintenance', 'decommissioned')),
    capacity    INTEGER NOT NULL DEFAULT 100,
    load_factor REAL NOT NULL DEFAULT 0.0,
    wg_subnet   TEXT NOT NULL,
    dns_server  TEXT NOT NULL DEFAULT '1.1.1.1',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_servers_region ON servers(region);
CREATE INDEX IF NOT EXISTS idx_servers_status ON servers(status);

CREATE TABLE IF NOT EXISTS peers (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    TEXT NOT NULL,
    server_id     UUID NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
    pubkey        TEXT NOT NULL,
    preshared_key TEXT,
    allowed_ips   TEXT[] NOT NULL DEFAULT '{}',
    assigned_ip   TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'active', 'failed', 'removed')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at    TIMESTAMPTZ,
    UNIQUE(account_id, server_id)
);

CREATE INDEX IF NOT EXISTS idx_peers_account ON peers(account_id);
CREATE INDEX IF NOT EXISTS idx_peers_server ON peers(server_id);
CREATE INDEX IF NOT EXISTS idx_peers_pubkey ON peers(pubkey);
CREATE INDEX IF NOT EXISTS idx_peers_status ON peers(status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_peers_active_server_ip
    ON peers(server_id, assigned_ip)
    WHERE status IN ('pending', 'active');

CREATE TABLE IF NOT EXISTS server_metrics (
    id         BIGSERIAL PRIMARY KEY,
    server_id  UUID NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
    timestamp  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    rx_bytes   BIGINT NOT NULL DEFAULT 0,
    tx_bytes   BIGINT NOT NULL DEFAULT 0,
    peer_count INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_server_metrics_server ON server_metrics(server_id, timestamp);
`

func Up(ctx context.Context, pool *pgxpool.Pool) error {
	if _, err := pool.Exec(ctx, schema); err != nil {
		return fmt.Errorf("wg-manager migrate: %w", err)
	}
	return nil
}
