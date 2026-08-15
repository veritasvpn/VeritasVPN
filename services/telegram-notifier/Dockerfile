FROM golang:1.22-alpine AS builder
RUN apk add --no-cache ca-certificates
WORKDIR /src
COPY services/telegram-notifier/go.mod services/telegram-notifier/go.sum* ./services/telegram-notifier/
WORKDIR /src/services/telegram-notifier
RUN GOWORK=off go mod download
COPY services/telegram-notifier/main.go .
RUN GOWORK=off CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/telegram-notifier .
FROM alpine:3.20
RUN apk add --no-cache ca-certificates
COPY --from=builder /out/telegram-notifier /usr/local/bin/telegram-notifier
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/telegram-notifier"]
