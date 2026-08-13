#!/bin/sh
set -eu
while true; do
  upnpc -a "${INTERNAL_IP}" "${INTERNAL_PORT}" "${EXTERNAL_PORT}" TCP 0 || true
  sleep 1800
done
