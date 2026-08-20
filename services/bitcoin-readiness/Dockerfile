FROM golang:1.22-alpine AS builder
WORKDIR /src
COPY services/bitcoin-readiness/go.mod ./services/bitcoin-readiness/
COPY services/bitcoin-readiness/main.go ./services/bitcoin-readiness/
WORKDIR /src/services/bitcoin-readiness
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/bitcoin-readiness .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=builder /out/bitcoin-readiness /usr/local/bin/bitcoin-readiness
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/bitcoin-readiness"]
