module github.com/veritasvpn/services/billing-svc

go 1.22

require (
	github.com/golang-jwt/jwt/v5 v5.2.1
	github.com/jackc/pgx/v5 v5.6.0
	github.com/nats-io/nats.go v1.36.0
	github.com/redis/go-redis/v9 v9.5.3
	github.com/veritasvpn/lib/config v0.0.0
	github.com/veritasvpn/lib/logging v0.0.0
	github.com/veritasvpn/lib/tokenhash v0.0.0
	go.uber.org/zap v1.27.0
)

require (
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	github.com/dgryski/go-rendezvous v0.0.0-20200823014737-9f7001d12a5f // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20231201235250-de7065d80cb9 // indirect
	github.com/jackc/puddle/v2 v2.2.1 // indirect
	github.com/klauspost/compress v1.17.9 // indirect
	github.com/nats-io/nkeys v0.4.7 // indirect
	github.com/nats-io/nuid v1.0.1 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	golang.org/x/crypto v0.24.0 // indirect
	golang.org/x/sync v0.7.0 // indirect
	golang.org/x/sys v0.21.0 // indirect
	golang.org/x/text v0.16.0 // indirect
)

replace (
	github.com/veritasvpn/lib/config => ../../lib/config
	github.com/veritasvpn/lib/logging => ../../lib/logging
	github.com/veritasvpn/lib/tokenhash => ../../lib/tokenhash
)
