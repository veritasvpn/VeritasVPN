# Stealth sidecar: WireGuard over TLS/WebSocket (wstunnel).
# Binary is baked in so the DaemonSet does not depend on a hostPath git checkout.
FROM alpine:3.22 AS fetch
ARG WSTUNNEL_VERSION=10.6.2
ARG WSTUNNEL_ARCH=linux_amd64
RUN apk add --no-cache curl ca-certificates \
 && curl -fL --retry 5 -o /tmp/wst.tar.gz \
      "https://github.com/erebe/wstunnel/releases/download/v${WSTUNNEL_VERSION}/wstunnel_${WSTUNNEL_VERSION}_${WSTUNNEL_ARCH}.tar.gz" \
 && tar -xzf /tmp/wst.tar.gz -C /tmp \
 && install -m 0755 "$(find /tmp -type f -name wstunnel | head -1)" /usr/local/bin/wstunnel \
 && /usr/local/bin/wstunnel --version

FROM alpine:3.22
RUN apk add --no-cache ca-certificates libgcc
COPY --from=fetch /usr/local/bin/wstunnel /usr/local/bin/wstunnel
# Bind :443 on hostNetwork requires root or net.ipv4.ip_unprivileged_port_start=0.
# Run as root with NET_BIND_SERVICE; DaemonSet also drops unnecessary caps.
EXPOSE 443
ENTRYPOINT ["/usr/local/bin/wstunnel"]
