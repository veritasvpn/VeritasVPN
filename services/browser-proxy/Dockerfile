FROM golang:1.25.13-alpine@sha256:1e0126852075c9c60731c8ba49088448b91f63e2aed97ca9d1a9791622a05946 AS build
WORKDIR /src
COPY services/browser-proxy/go.mod ./
COPY services/browser-proxy/main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /browser-proxy .

FROM alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce
RUN apk add --no-cache ca-certificates && apk upgrade --no-cache && addgroup -S veritas && adduser -S -G veritas veritas
COPY --from=build /browser-proxy /usr/local/bin/browser-proxy
USER veritas
EXPOSE 1080
ENTRYPOINT ["browser-proxy"]
