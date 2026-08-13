FROM golang:1.22-alpine AS builder

RUN apk add --no-cache git ca-certificates

WORKDIR /app

COPY api/ api/
COPY lib/config/ lib/config/
COPY lib/logging/ lib/logging/
COPY lib/crypto/ lib/crypto/
COPY lib/jwt/ lib/jwt/
COPY services/auth-svc/ services/auth-svc/

WORKDIR /app/services/auth-svc
RUN GOWORK=off go mod tidy && GOWORK=off CGO_ENABLED=0 GOOS=linux go build -o /app/auth-svc ./cmd/server/

FROM alpine:3.19
RUN apk add --no-cache ca-certificates tzdata
COPY --from=builder /app/auth-svc /usr/local/bin/auth-svc
EXPOSE 8080
CMD ["/usr/local/bin/auth-svc"]
