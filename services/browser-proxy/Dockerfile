FROM golang:1.24-alpine@sha256:8bee1901f1e530bfb4a7850aa7a479d17ae3a18beb6e09064ed54cfd245b7191 AS build
WORKDIR /src
COPY services/browser-proxy/go.mod ./
COPY services/browser-proxy/main.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /browser-proxy .

FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
RUN apk add --no-cache ca-certificates && addgroup -S veritas && adduser -S -G veritas veritas
COPY --from=build /browser-proxy /usr/local/bin/browser-proxy
USER veritas
EXPOSE 1080
ENTRYPOINT ["browser-proxy"]
