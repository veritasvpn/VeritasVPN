FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc

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
