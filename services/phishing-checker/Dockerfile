FROM golang:1.26.9-alpine@sha256:cdfd4fe2da6b225d8b40c6b7a105736e548e83ff56d5d8f9394446eeb5eb84e0 AS builder
WORKDIR /src
COPY lib/netpolicy ./lib/netpolicy
COPY services/phishing-checker/go.mod ./services/phishing-checker/
COPY services/phishing-checker/main.go ./services/phishing-checker/
WORKDIR /src/services/phishing-checker
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/phishing-checker .

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
COPY --from=builder /out/phishing-checker /usr/local/bin/phishing-checker
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/phishing-checker"]
