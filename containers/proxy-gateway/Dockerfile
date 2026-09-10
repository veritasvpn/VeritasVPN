FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc

ARG TARGETARCH

RUN apk add --no-cache curl bash

RUN case "${TARGETARCH}" in \
      amd64) gost_arch=amd64; expected=3417fd178ef9f86a5bda4e56d90739411df9d9ff3657724abc5614bb32e50f4b ;; \
      arm64) gost_arch=armv8; expected=9b606482837e1796009e1647994e7d6e2747c10ea577dd9a13e37b9b43a3fc40 ;; \
      *) echo "Unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && curl -fsSL -o /tmp/gost.gz "https://github.com/ginuerzh/gost/releases/download/v2.11.5/gost-linux-${gost_arch}-2.11.5.gz" \
    && printf '%s  %s\n' "$expected" /tmp/gost.gz | sha256sum -c - \
    && gunzip -c /tmp/gost.gz > /usr/local/bin/gost \
    && rm -f /tmp/gost.gz \
    && chmod 0755 /usr/local/bin/gost

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 51820/udp 1080/tcp
ENTRYPOINT ["/entrypoint.sh"]
