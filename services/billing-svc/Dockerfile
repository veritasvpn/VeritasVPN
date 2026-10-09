FROM golang:1.26.9-alpine@sha256:cdfd4fe2da6b225d8b40c6b7a105736e548e83ff56d5d8f9394446eeb5eb84e0 AS builder

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
RUN apk add --no-cache ca-certificates tzdata && apk upgrade --no-cache
RUN addgroup -g 65532 -S nonroot && adduser -u 65532 -S -G nonroot nonroot
COPY --from=builder /app/billing-svc /usr/local/bin/billing-svc
# Numeric so the kubelet can enforce runAsNonRoot without resolving the name.
USER 65532:65532
EXPOSE 8080
CMD ["/usr/local/bin/billing-svc"]
