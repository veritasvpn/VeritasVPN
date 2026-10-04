#!/usr/bin/env bash
# GET /api/v1/wg/ must not use the peer-provisioning rate limit.
# Connected clients poll GET /api/v1/wg/peers about every 5 seconds and, on the
# VPN, share one egress IP with the account devices page. nginx answers 503
# when provisioning_limit (10r/m) is exceeded. An empty limit key is not
# counted, so GET/HEAD stay off that budget while POST/PATCH/DELETE stay on it.
# limit_req cannot be nested in limit_except, so the method split is a map.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
python3 - "$ROOT" <<'PY'
import pathlib, sys

root = pathlib.Path(sys.argv[1])
files = [
    root / "deploy/k8s/base/nginx-configmap.yaml",
    root / "deploy/nginx/nginx.prod.conf",
    root / "website/nginx.conf",
]
failed = False

def location_block(text: str, header: str) -> str:
    start = text.find(header)
    if start < 0:
        return ""
    brace = text.find("{", start)
    if brace < 0:
        return ""
    depth = 0
    for index, ch in enumerate(text[brace:], brace):
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[brace : index + 1]
    return ""

def map_block(text: str, variable: str) -> str:
    header = f"map $request_method {variable}"
    return location_block(text, header)

for path in files:
    text = path.read_text()
    block = location_block(text, "location /api/v1/wg/")
    if not block:
        print(f"FAIL: {path} has no location /api/v1/wg/", file=sys.stderr)
        failed = True
        continue
    applies = "limit_req zone=provisioning_limit" in block
    if path.name != "nginx-configmap.yaml":
        if applies:
            print(f"FAIL: {path} applies provisioning_limit to /api/v1/wg/ without a GET exemption", file=sys.stderr)
            failed = True
        else:
            print(f"OK: {path} does not rate-limit GET /api/v1/wg/ peers")
        continue
    if "zone=provisioning_limit:10m rate=10r/m" not in text:
        print(f"FAIL: {path} provisioning budget is no longer 10r/m", file=sys.stderr)
        failed = True
    if "limit_req_zone $provisioning_limit_key zone=provisioning_limit:10m rate=10r/m;" not in text:
        print(f"FAIL: {path} provisioning zone is not keyed by $provisioning_limit_key", file=sys.stderr)
        failed = True
    if not applies:
        print(f"FAIL: {path} no longer limits peer provisioning", file=sys.stderr)
        failed = True
    mapped = map_block(text, "$provisioning_limit_key")
    if not mapped:
        print(f"FAIL: {path} missing map from request method to provisioning key", file=sys.stderr)
        failed = True
    else:
        compact = " ".join(mapped.split())
        if 'GET ""' not in compact and "GET '';" not in compact and 'GET "";' not in compact:
            print(f"FAIL: {path} GET is still counted by the provisioning limit", file=sys.stderr)
            failed = True
        if "default $rate_limit_key" not in compact:
            print(f"FAIL: {path} non-GET provisioning key is not the client address", file=sys.stderr)
            failed = True
    if not failed:
        print(f"OK: {path} limits peer changes at 10r/m and does not count GET")

if failed:
    sys.exit(1)
PY
