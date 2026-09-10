# Stealth sidecar: WireGuard over TLS/WebSocket (wstunnel).
# Binary is baked in so the DaemonSet does not depend on a hostPath git checkout.
FROM alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce AS fetch
ARG WSTUNNEL_VERSION=10.6.2
ARG WSTUNNEL_ARCH=linux_amd64
RUN apk add --no-cache curl ca-certificates \
 && curl -fL --retry 5 -o /tmp/wst.tar.gz \
      "https://github.com/erebe/wstunnel/releases/download/v${WSTUNNEL_VERSION}/wstunnel_${WSTUNNEL_VERSION}_${WSTUNNEL_ARCH}.tar.gz" \
 && case "$WSTUNNEL_ARCH" in \
      linux_amd64) expected=db6064cca0515b67f8652e201cff8e27553b8cbb7216b2e19241311e34868e6e ;; \
      linux_arm64) expected=26bb36b856948255bec7cd71a39df5f8912acdd7a47a9ccd4044a9b80ced108d ;; \
      *) echo "unsupported wstunnel architecture: $WSTUNNEL_ARCH" >&2; exit 1 ;; \
    esac \
 && printf '%s  %s\n' "$expected" /tmp/wst.tar.gz | sha256sum -c - \
 && tar -xzf /tmp/wst.tar.gz -C /tmp \
 && install -m 0755 "$(find /tmp -type f -name wstunnel | head -1)" /usr/local/bin/wstunnel \
 && /usr/local/bin/wstunnel --version

FROM alpine:3.22@sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce
RUN apk add --no-cache ca-certificates libgcc
COPY --from=fetch /usr/local/bin/wstunnel /usr/local/bin/wstunnel
# The DaemonSet runs this image as a non-root UID with only NET_BIND_SERVICE.
EXPOSE 443
ENTRYPOINT ["/usr/local/bin/wstunnel"]
