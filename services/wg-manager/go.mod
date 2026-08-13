module github.com/veritasvpn/services/wg-manager

go 1.22

require (
	github.com/golang-jwt/jwt/v5 v5.2.1
	github.com/jackc/pgx/v5 v5.6.0
	github.com/nats-io/nats.go v1.35.0
	github.com/redis/go-redis/v9 v9.5.3
	github.com/veritasvpn/lib/config v0.0.0
	github.com/veritasvpn/lib/logging v0.0.0
)

replace (
	github.com/veritasvpn/lib/config => ../../lib/config
	github.com/veritasvpn/lib/logging => ../../lib/logging
)
