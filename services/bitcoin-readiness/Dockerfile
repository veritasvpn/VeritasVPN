FROM golang:1.25.13-alpine@sha256:1e0126852075c9c60731c8ba49088448b91f63e2aed97ca9d1a9791622a05946 AS builder
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
