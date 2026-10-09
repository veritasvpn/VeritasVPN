FROM golang:1.26.9-alpine@sha256:cdfd4fe2da6b225d8b40c6b7a105736e548e83ff56d5d8f9394446eeb5eb84e0 AS build
WORKDIR /src
COPY lib/netpolicy ./lib/netpolicy
COPY services/browser-proxy ./services/browser-proxy
WORKDIR /src/services/browser-proxy
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /browser-proxy .

FROM alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce
RUN apk add --no-cache ca-certificates && apk upgrade --no-cache \
    && addgroup -g 65532 -S veritas && adduser -u 65532 -S -G veritas veritas
COPY --from=build /browser-proxy /usr/local/bin/browser-proxy
# Numeric so the kubelet can enforce runAsNonRoot without resolving the name.
USER 65532:65532
EXPOSE 1080
ENTRYPOINT ["browser-proxy"]
