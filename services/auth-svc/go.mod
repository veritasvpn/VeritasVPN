module github.com/veritasvpn/services/auth-svc

go 1.22

require (
	github.com/jackc/pgx/v5 v5.6.0
	github.com/nats-io/nats.go v1.36.0
	github.com/redis/go-redis/v9 v9.5.3
	github.com/veritasvpn/lib/config v0.0.0
	github.com/veritasvpn/lib/crypto v0.0.0
	github.com/veritasvpn/lib/jwt v0.0.0
	github.com/veritasvpn/lib/logging v0.0.0
	google.golang.org/grpc v1.64.0
)

require (
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	github.com/dgryski/go-rendezvous v0.0.0-20200823014737-9f7001d12a5f // indirect
	github.com/golang-jwt/jwt/v5 v5.2.1 // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20231201235250-de7065d80cb9 // indirect
	github.com/jackc/puddle/v2 v2.2.1 // indirect
	github.com/klauspost/compress v1.17.9 // indirect
	github.com/nats-io/nkeys v0.4.7 // indirect
	github.com/nats-io/nuid v1.0.1 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	go.uber.org/zap v1.27.0 // indirect
	golang.org/x/crypto v0.24.0 // indirect
	golang.org/x/net v0.26.0 // indirect
	golang.org/x/sync v0.7.0 // indirect
	golang.org/x/sys v0.21.0 // indirect
	golang.org/x/text v0.16.0 // indirect
	google.golang.org/protobuf v1.34.2 // indirect
)

replace (
	github.com/veritasvpn/api => ../../api
	github.com/veritasvpn/lib/config => ../../lib/config
	github.com/veritasvpn/lib/crypto => ../../lib/crypto
	github.com/veritasvpn/lib/jwt => ../../lib/jwt
	github.com/veritasvpn/lib/logging => ../../lib/logging
)
