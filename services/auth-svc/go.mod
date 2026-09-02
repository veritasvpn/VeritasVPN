module github.com/veritasvpn/services/auth-svc

go 1.25.0

require (
	github.com/jackc/pgx/v5 v5.9.2
	github.com/nats-io/nats.go v1.36.0
	github.com/redis/go-redis/v9 v9.22.0
	github.com/veritasvpn/api v0.0.0-00010101000000-000000000000
	github.com/veritasvpn/lib/config v0.0.0
	github.com/veritasvpn/lib/crypto v0.0.0-00010101000000-000000000000
	github.com/veritasvpn/lib/jwt v0.0.0
	github.com/veritasvpn/lib/logging v0.0.0
	go.uber.org/zap v1.27.0
	google.golang.org/grpc v1.83.1
)

require (
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	github.com/golang-jwt/jwt/v5 v5.2.2 // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20240606120523-5a60cdf6a761 // indirect
	github.com/jackc/puddle/v2 v2.2.2 // indirect
	github.com/klauspost/compress v1.17.9 // indirect
	github.com/nats-io/nkeys v0.4.7 // indirect
	github.com/nats-io/nuid v1.0.1 // indirect
	go.uber.org/atomic v1.11.0 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	golang.org/x/crypto v0.55.0 // indirect
	golang.org/x/net v0.57.0 // indirect
	golang.org/x/sync v0.22.0 // indirect
	golang.org/x/sys v0.47.0 // indirect
	golang.org/x/text v0.41.0 // indirect
	google.golang.org/genproto/googleapis/rpc v0.0.0-20260526163538-3dc84a4a5aaa // indirect
	google.golang.org/protobuf v1.36.11 // indirect
)

replace (
	github.com/veritasvpn/api => ../../api
	github.com/veritasvpn/lib/config => ../../lib/config
	github.com/veritasvpn/lib/crypto => ../../lib/crypto
	github.com/veritasvpn/lib/jwt => ../../lib/jwt
	github.com/veritasvpn/lib/logging => ../../lib/logging
)
