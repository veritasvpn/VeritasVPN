FROM golang:1.26.11-alpine@sha256:ecb67f987aa1a57794c91d19ade7ae0209f2f0f5496aaef9e6ae85ddf92d02ab AS builder
RUN apk add --no-cache git
WORKDIR /src
COPY lib/logging/ lib/logging/
COPY services/veritas-agent/ services/veritas-agent/
WORKDIR /src/services/veritas-agent
RUN go mod tidy
RUN CGO_ENABLED=0 go build -o /usr/local/bin/veritas-agent ./cmd/agent/

FROM alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce
RUN apk add --no-cache ca-certificates wireguard-tools nftables iproute2 && apk upgrade --no-cache
COPY --from=builder /usr/local/bin/veritas-agent /usr/local/bin/veritas-agent
CMD ["/usr/local/bin/veritas-agent"]
