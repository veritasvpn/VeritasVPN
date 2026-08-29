FROM golang:1.25.13-alpine@sha256:1e0126852075c9c60731c8ba49088448b91f63e2aed97ca9d1a9791622a05946 AS builder

RUN apk add --no-cache git ca-certificates

WORKDIR /app

COPY lib/config/ lib/config/
COPY lib/logging/ lib/logging/
COPY lib/jwt/ lib/jwt/
COPY services/wg-manager/ services/wg-manager/

WORKDIR /app/services/wg-manager
RUN go mod tidy
RUN CGO_ENABLED=0 GOOS=linux go build -o /app/wg-manager ./cmd/server/

FROM alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce
RUN apk add --no-cache ca-certificates tzdata
COPY --from=builder /app/wg-manager /usr/local/bin/wg-manager
EXPOSE 8080
CMD ["/usr/local/bin/wg-manager"]
