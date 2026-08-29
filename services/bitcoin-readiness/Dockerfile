FROM golang:1.24-alpine@sha256:8bee1901f1e530bfb4a7850aa7a479d17ae3a18beb6e09064ed54cfd245b7191 AS builder
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
