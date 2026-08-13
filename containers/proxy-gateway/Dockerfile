FROM alpine:3.20

ARG TARGETARCH

RUN apk add --no-cache \
    curl \
    bash

RUN case "${TARGETARCH}" in \
      amd64|arm64) gost_arch="${TARGETARCH}" ;; \
      *) echo "Unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && curl -fsSL "https://github.com/ginuerzh/gost/releases/download/v2.11.5/gost-linux-${gost_arch}-2.11.5.gz" \
    | gunzip > /usr/local/bin/gost && chmod +x /usr/local/bin/gost

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 51820/udp 1080/tcp
ENTRYPOINT ["/entrypoint.sh"]
