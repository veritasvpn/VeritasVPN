#!/usr/bin/env bash
# Print Ed25519 JWT env assignments suitable for appending to .env.
# Does not write secrets to disk beyond stdout.
set -euo pipefail

KID="${JWT_ACTIVE_KEY_ID:-veritas-$(date -u +%Y%m%dT%H%M%SZ)}"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

openssl genpkey -algorithm Ed25519 -out "$WORKDIR/private.pem" >/dev/null 2>&1
openssl pkey -in "$WORKDIR/private.pem" -pubout -out "$WORKDIR/public.pem" >/dev/null 2>&1

python3 - "$WORKDIR/private.pem" "$WORKDIR/public.pem" "$KID" <<'PY'
import json, pathlib, sys
private = pathlib.Path(sys.argv[1]).read_text()
public = pathlib.Path(sys.argv[2]).read_text()
kid = sys.argv[3]
# Single-line .env values with escaped newlines.
def esc(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')
print(f'JWT_ACTIVE_KEY_ID={kid}')
print(f'JWT_ED25519_PRIVATE_KEY="{esc(private)}"')
print(f'JWT_ED25519_PUBLIC_KEYS={json.dumps({kid: public})}')
print('JWT_ISSUER=https://api.veritasvpn.cloud')
print('JWT_AUDIENCE=veritasvpn-api')
PY
