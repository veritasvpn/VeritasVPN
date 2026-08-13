package model

import "time"

type Server struct {
	ID         string    `db:"id"`
	Hostname   string    `db:"hostname"`
	Region     string    `db:"region"`
	City       string    `db:"city"`
	Country    string    `db:"country"`
	PublicIP   string    `db:"public_ip"`
	WGPort     int32     `db:"wg_port"`
	PublicKey  string    `db:"public_key"`
	Status     string    `db:"status"`
	Capacity   int32     `db:"capacity"`
	LoadFactor float64   `db:"load_factor"`
	WGSubnet   string    `db:"wg_subnet"`
	DNSServer  string    `db:"dns_server"`
	CreatedAt  time.Time `db:"created_at"`
	UpdatedAt  time.Time `db:"updated_at"`
}

type Peer struct {
	ID           string     `db:"id"`
	AccountID    string     `db:"account_id"`
	ServerID     string     `db:"server_id"`
	Pubkey       string     `db:"pubkey"`
	PresharedKey *string    `db:"preshared_key"`
	AllowedIPs   []string   `db:"allowed_ips"`
	AssignedIP   string     `db:"assigned_ip"`
	Status       string     `db:"status"`
	CreatedAt    time.Time  `db:"created_at"`
	ExpiresAt    *time.Time `db:"expires_at"`
}

type ServerMetric struct {
	ID        int64     `db:"id"`
	ServerID  string    `db:"server_id"`
	Timestamp time.Time `db:"timestamp"`
	RXBytes   int64     `db:"rx_bytes"`
	TXBytes   int64     `db:"tx_bytes"`
	PeerCount int32     `db:"peer_count"`
}
