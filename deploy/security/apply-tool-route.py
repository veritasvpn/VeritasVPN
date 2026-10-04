"""Scoped nginx patch: preserve all other live routes; verify and roll back.

Run with sudo on the Dell after auth-svc's authenticated tool-limit is ready.
"""
import json
import os
import pathlib
import re
import subprocess
import tempfile

os.umask(0o077)
def kube(*args, body=None):
    return subprocess.check_output(["kubectl", "-n", "veritas", *args], input=body)

source = (pathlib.Path(__file__).resolve().parents[1] / "k8s/base/nginx-configmap.yaml").read_text()
block = re.search(r"        location = /api/v1/auth/tool-limit \{.*?\n        \}", source, re.S).group(0)
block = "\n".join(line[4:] for line in block.splitlines())
original = json.loads(kube("get", "configmap", "nginx-config", "-o", "json"))["data"]["default.conf"]
updated = original
if "location = /api/v1/auth/tool-limit {" not in original:
    assert original.count("    location /api/v1/auth/ {") == 1
    updated = original.replace("    location /api/v1/auth/ {", block + "\n\n    location /api/v1/auth/ {")
else:
    assert block in original, "existing quota route differs; review rather than overwrite"
backup = pathlib.Path(tempfile.mkdtemp(prefix="veritas-tool-route-", dir="/var/tmp")) / "default.conf"
backup.write_text(original)
print(f"Original nginx configuration retained at {backup}", flush=True)
def apply(config):
    kube("patch", "configmap", "nginx-config", "--type", "merge", "--patch-file", "/dev/stdin",
         body=json.dumps({"data": {"default.conf": config}}).encode())
    kube("rollout", "restart", "deployment/nginx")
    kube("rollout", "status", "deployment/nginx", "--timeout=120s")
    kube("exec", "deployment/nginx", "--", "nginx", "-t")
try:
    apply(updated)
except Exception:
    apply(original)
    raise
print("Quota route deployed; nginx configuration and readiness passed.")
