FROM golang:1.26.11-alpine@sha256:ecb67f987aa1a57794c91d19ade7ae0209f2f0f5496aaef9e6ae85ddf92d02ab AS builder
WORKDIR /src
COPY services/bitcoin-readiness/go.mod ./services/bitcoin-readiness/
COPY services/bitcoin-readiness/main.go ./services/bitcoin-readiness/
WORKDIR /src/services/bitcoin-readiness
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/bitcoin-readiness .

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
COPY --from=builder /out/bitcoin-readiness /usr/local/bin/bitcoin-readiness
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/bitcoin-readiness"]
