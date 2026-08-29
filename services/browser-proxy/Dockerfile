FROM golang:1.24-alpine@sha256:8bee1901f1e530bfb4a7850aa7a479d17ae3a18beb6e09064ed54cfd245b7191 AS build
WORKDIR /src
COPY services/browser-proxy/go.mod ./
COPY services/browser-proxy/main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /browser-proxy .

FROM alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce
RUN apk add --no-cache ca-certificates && addgroup -S veritas && adduser -S -G veritas veritas
COPY --from=build /browser-proxy /usr/local/bin/browser-proxy
USER veritas
EXPOSE 1080
ENTRYPOINT ["browser-proxy"]
