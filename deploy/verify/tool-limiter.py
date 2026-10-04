"""Verify the deployed limiter with a reserved test identity, not a customer.

Run on the Dell with permission to read veritas-secrets. Never prints the secret.
"""
import base64
import concurrent.futures
import hashlib
import hmac
import json
import subprocess
import time
import urllib.error
import urllib.request

secret = base64.b64decode(subprocess.check_output([
    "kubectl", "-n", "veritas", "get", "secret", "veritas-secrets",
    "-o", "jsonpath={.data.TOOLS_RATE_LIMIT_SECRET}",
]))
# A fresh reserved documentation address per invocation avoids old test quotas.
ip = "2001:db8::" + format(time.time_ns() & 0xffffffffffffffff, "x")[-4:]

def attempt(forged=False):
    timestamp = str(int(time.time()))
    message = f"{timestamp}\ncheck-dns-session\n{ip}".encode()
    signature = hmac.new(secret, message, hashlib.sha256).hexdigest()
    request = urllib.request.Request(
        "https://api.veritasvpn.cloud/api/v1/auth/tool-limit",
        data=json.dumps({"bucket": "check-dns-session", "ip": ip}).encode(),
        headers={"Content-Type": "application/json", "X-Tool-Timestamp": timestamp,
                 "X-Tool-Signature": "0" * 64 if forged else signature},
    )
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code

assert attempt(True) == 401, "forged request must be rejected"
with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
    results = list(executor.map(lambda _: attempt(), range(30)))
assert results.count(204) == 20 and results.count(429) == 10, results
print("Public limiter: forged request rejected; exactly 20/30 allowed, 10 limited.")
