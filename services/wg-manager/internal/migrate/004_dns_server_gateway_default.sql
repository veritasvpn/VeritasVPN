-- Prefer gateway-shaped DNS defaults for newly registered servers.
-- Runtime registration already overwrites dns_server with the agent gateway IP.
ALTER TABLE servers ALTER COLUMN dns_server SET DEFAULT '10.0.0.1';
