# Site-local values (not committed)

Public overlays use placeholders such as `REPLACE_ME_PUBLIC_IP`.

On the production node, before `kubectl apply -k deploy/k8s/overlays/k3s`:

1. Symlink or clone the repo to `/opt/veritasvpn` (or set `REPO_ROOT`).
2. Patch ConfigMap `PUBLIC_IP`, `BROWSER_PROXY_HOST`, `BROWSER_EXPECTED_EGRESS_IP`,
   and `STEALTH_ENDPOINT_HOST` to your egress address (or maintain a private
   overlay that is **not** pushed to the public GitHub repo).
3. For image pushes: `kubectl -n veritas port-forward svc/registry 31500:5000`
   then `REGISTRY=localhost:31500 TAG=… bash deploy/k8s/scripts/push-images.sh`.
