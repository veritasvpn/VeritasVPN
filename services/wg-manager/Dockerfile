FROM golang:1.24-alpine AS builder

RUN apk add --no-cache git ca-certificates

WORKDIR /app

COPY lib/config/ lib/config/
COPY lib/logging/ lib/logging/
COPY services/wg-manager/ services/wg-manager/

WORKDIR /app/services/wg-manager
RUN go mod tidy
RUN CGO_ENABLED=0 GOOS=linux go build -o /app/wg-manager ./cmd/server/

FROM alpine:3.22
RUN apk add --no-cache ca-certificates tzdata
COPY --from=builder /app/wg-manager /usr/local/bin/wg-manager
EXPOSE 8080
CMD ["/usr/local/bin/wg-manager"]
