FROM alpine:3.20
RUN apk add --no-cache miniupnpc
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
