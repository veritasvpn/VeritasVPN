#!/bin/bash
set -e
echo "[veritas-proxy] Starting SOCKS5 proxy on 0.0.0.0:1080" >&2
exec gost -L "socks5://:1080"
