#!/usr/bin/env python3
"""Upload encrypted backup sidecars to Cloudflare R2 using the S3 API."""
import argparse
import datetime as dt
import hashlib
import hmac
import os
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen


def _hmac(key: bytes, value: str) -> bytes:
    return hmac.new(key, value.encode(), hashlib.sha256).digest()


def _request(method: str, url: str, access_key: str, secret_key: str, payload: bytes | None = None) -> None:
    parsed = urlsplit(url)
    host = parsed.netloc
    path = parsed.path or "/"
    payload_hash = hashlib.sha256(payload or b"").hexdigest()
    now = dt.datetime.now(dt.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    short_date = now.strftime("%Y%m%d")
    scope = f"{short_date}/auto/s3/aws4_request"
    canonical_headers = (
        f"host:{host}\n"
        f"x-amz-content-sha256:{payload_hash}\n"
        f"x-amz-date:{amz_date}\n"
    )
    canonical_request = "\n".join(
        [method, path, parsed.query, canonical_headers, "host;x-amz-content-sha256;x-amz-date", payload_hash]
    )
    string_to_sign = "\n".join(
        ["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical_request.encode()).hexdigest()]
    )
    signing_key = _hmac(_hmac(_hmac(_hmac(("AWS4" + secret_key).encode(), short_date), "auto"), "s3"), "aws4_request")
    signature = hmac.new(signing_key, string_to_sign.encode(), hashlib.sha256).hexdigest()
    headers = {
        "Host": host,
        "x-amz-content-sha256": payload_hash,
        "x-amz-date": amz_date,
        "Authorization": f"AWS4-HMAC-SHA256 Credential={access_key}/{scope}, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature={signature}",
    }
    request = Request(url, data=payload, headers=headers, method=method)
    try:
        with urlopen(request, timeout=60) as response:
            if response.status not in (200, 201, 204):
                raise RuntimeError(f"R2 {method} returned HTTP {response.status}")
    except (HTTPError, URLError) as exc:
        detail = exc.read().decode(errors="replace") if isinstance(exc, HTTPError) else str(exc)
        raise RuntimeError(f"R2 {method} failed: {detail}") from exc


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--file", action="append", required=True)
    args = parser.parse_args()
    endpoint = os.environ["R2_ENDPOINT"].rstrip("/")
    bucket = os.environ["R2_BUCKET"]
    access_key = os.environ["R2_ACCESS_KEY_ID"]
    secret_key = os.environ["R2_SECRET_ACCESS_KEY"]
    for file_name in args.file:
        path = Path(file_name)
        object_key = f"{args.prefix.strip('/')}/{path.name}"
        url = f"{endpoint}/{quote(bucket, safe='')}/{quote(object_key, safe='/~-_.')}"
        payload = path.read_bytes()
        _request("PUT", url, access_key, secret_key, payload)
        _request("HEAD", url, access_key, secret_key)
        print(f"[r2] uploaded and verified {object_key} ({len(payload)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
