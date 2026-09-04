module github.com/veritasvpn/services/wg-manager

go 1.25.0

require (
	github.com/jackc/pgx/v5 v5.9.2
	github.com/nats-io/nats.go v1.53.1
	github.com/redis/go-redis/v9 v9.6.3
	github.com/veritasvpn/lib/config v0.0.0
	github.com/veritasvpn/lib/jwt v0.0.0
	github.com/veritasvpn/lib/logging v0.0.0
	github.com/veritasvpn/lib/tokenhash v0.0.0
	go.uber.org/zap v1.27.0
)

require github.com/golang-jwt/jwt/v5 v5.2.2 // indirect

require (
	github.com/beorn7/perks v1.0.1 // indirect
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	github.com/dgryski/go-rendezvous v0.0.0-20200823014737-9f7001d12a5f // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20240606120523-5a60cdf6a761 // indirect
	github.com/jackc/puddle/v2 v2.2.2 // indirect
	github.com/klauspost/compress v1.18.5 // indirect
	github.com/munnerz/goautoneg v0.0.0-20191010083416-a7dc8b61c822 // indirect
	github.com/nats-io/nkeys v0.4.15 // indirect
	github.com/nats-io/nuid v1.0.1 // indirect
	github.com/prometheus/client_golang v1.20.5
	github.com/prometheus/client_model v0.6.1 // indirect
	github.com/prometheus/common v0.55.0 // indirect
	github.com/prometheus/procfs v0.15.1 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	golang.org/x/crypto v0.55.0 // indirect
	golang.org/x/sync v0.22.0 // indirect
	golang.org/x/sys v0.47.0 // indirect
	golang.org/x/text v0.41.0 // indirect
	google.golang.org/protobuf v1.36.11 // indirect
)

replace (
	github.com/veritasvpn/lib/config => ../../lib/config
	github.com/veritasvpn/lib/jwt => ../../lib/jwt
	github.com/veritasvpn/lib/logging => ../../lib/logging
	github.com/veritasvpn/lib/tokenhash => ../../lib/tokenhash
)
