FROM golang:1.22-alpine AS builder

RUN apk add --no-cache git ca-certificates

WORKDIR /app

COPY go.work go.work.sum* ./
COPY lib/config/ lib/config/
COPY lib/logging/ lib/logging/
COPY services/billing-svc/ services/billing-svc/

WORKDIR /app/services/billing-svc
RUN GOWORK=off go mod tidy && GOWORK=off CGO_ENABLED=0 GOOS=linux go build -o /app/billing-svc ./cmd/server/

FROM alpine:3.22
RUN apk add --no-cache ca-certificates tzdata
COPY --from=builder /app/billing-svc /usr/local/bin/billing-svc
EXPOSE 8080
CMD ["/usr/local/bin/billing-svc"]
