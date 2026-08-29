module github.com/veritasvpn/services/billing-svc

go 1.25.0

require (
	github.com/golang-jwt/jwt/v5 v5.2.2
	github.com/jackc/pgx/v5 v5.9.2
	github.com/nats-io/nats.go v1.36.0
	github.com/redis/go-redis/v9 v9.6.3
	github.com/veritasvpn/lib/config v0.0.0
	github.com/veritasvpn/lib/jwt v0.0.0
	github.com/veritasvpn/lib/logging v0.0.0
	github.com/veritasvpn/lib/tokenhash v0.0.0
	go.uber.org/zap v1.27.0
)

require (
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	github.com/dgryski/go-rendezvous v0.0.0-20200823014737-9f7001d12a5f // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20240606120523-5a60cdf6a761 // indirect
	github.com/jackc/puddle/v2 v2.2.2 // indirect
	github.com/klauspost/compress v1.17.9 // indirect
	github.com/nats-io/nkeys v0.4.7 // indirect
	github.com/nats-io/nuid v1.0.1 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	golang.org/x/crypto v0.53.0 // indirect
	golang.org/x/sync v0.21.0 // indirect
	golang.org/x/sys v0.46.0 // indirect
	golang.org/x/text v0.39.0 // indirect
)

replace (
	github.com/veritasvpn/lib/config => ../../lib/config
	github.com/veritasvpn/lib/jwt => ../../lib/jwt
	github.com/veritasvpn/lib/logging => ../../lib/logging
	github.com/veritasvpn/lib/tokenhash => ../../lib/tokenhash
)
