FROM golang:1.26.10-alpine@sha256:9ce231b28998b32e116927c61aa5c406183061d4f3f2d61b292c88e7f6bdf7ca AS builder
RUN apk add --no-cache ca-certificates
WORKDIR /src
COPY services/telegram-notifier/go.mod services/telegram-notifier/go.sum* ./services/telegram-notifier/
WORKDIR /src/services/telegram-notifier
RUN GOWORK=off go mod download
COPY services/telegram-notifier/main.go .
RUN GOWORK=off CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/telegram-notifier .
FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
RUN apk add --no-cache ca-certificates
COPY --from=builder /out/telegram-notifier /usr/local/bin/telegram-notifier
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/telegram-notifier"]
