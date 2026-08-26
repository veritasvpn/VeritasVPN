-- 002_port_forwards.sql
-- Premium inbound port forwarding (DNAT on VPN nodes).

CREATE TABLE IF NOT EXISTS port_forwards (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    TEXT NOT NULL,
    peer_id       UUID NOT NULL REFERENCES peers(id) ON DELETE CASCADE,
    protocol      TEXT NOT NULL CHECK (protocol IN ('tcp', 'udp')),
    external_port INT NOT NULL CHECK (external_port >= 1024 AND external_port <= 65535),
    internal_port INT NOT NULL CHECK (internal_port >= 1 AND internal_port <= 65535),
    status        TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'active', 'error', 'removed')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_port_forwards_account ON port_forwards(account_id);
CREATE INDEX IF NOT EXISTS idx_port_forwards_peer ON port_forwards(peer_id);
CREATE INDEX IF NOT EXISTS idx_port_forwards_status ON port_forwards(status);

CREATE UNIQUE INDEX IF NOT EXISTS idx_port_forwards_active_external
    ON port_forwards(protocol, external_port)
    WHERE status IN ('pending', 'active');
