FROM golang:1.24-alpine AS build
WORKDIR /src
COPY services/browser-proxy/go.mod ./
COPY services/browser-proxy/main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /browser-proxy .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates && addgroup -S veritas && adduser -S -G veritas veritas
COPY --from=build /browser-proxy /usr/local/bin/browser-proxy
USER veritas
EXPOSE 1080
ENTRYPOINT ["browser-proxy"]
