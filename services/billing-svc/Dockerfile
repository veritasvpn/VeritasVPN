FROM golang:1.24-alpine@sha256:8bee1901f1e530bfb4a7850aa7a479d17ae3a18beb6e09064ed54cfd245b7191 AS builder

RUN apk add --no-cache git ca-certificates

WORKDIR /app

COPY go.work go.work.sum* ./
COPY lib/config/ lib/config/
COPY lib/logging/ lib/logging/
COPY lib/jwt/ lib/jwt/
COPY lib/tokenhash/ lib/tokenhash/
COPY services/billing-svc/ services/billing-svc/

WORKDIR /app/services/billing-svc
RUN GOWORK=off go mod tidy && GOWORK=off CGO_ENABLED=0 GOOS=linux go build -o /app/billing-svc ./cmd/server/

FROM alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce
RUN apk add --no-cache ca-certificates tzdata
COPY --from=builder /app/billing-svc /usr/local/bin/billing-svc
EXPOSE 8080
CMD ["/usr/local/bin/billing-svc"]
