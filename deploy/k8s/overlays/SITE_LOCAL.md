# Site-local values (not committed)

Public overlays carry placeholders such as `REPLACE_ME_PUBLIC_IP` so the node's
real addresses stay out of the public repository. That means **`overlays/k3s`
must never be applied directly on the production node** — it would overwrite the
live ConfigMap with the literal placeholder and break the WireGuard endpoint,
the stealth endpoint, and browser-proxy egress validation.

`apply.sh` refuses to apply any overlay that still renders a `REPLACE_ME`, so
this failure mode is now caught before it reaches the cluster rather than after.

## The site overlay

The production node keeps an untracked `overlays/site` that wraps `k3s` and
substitutes the real values. It is listed in `.gitignore`, so it never leaves
the host.

Create `deploy/k8s/overlays/site/kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: veritas

resources:
  - ../k3s

patches:
  - target:
      kind: ConfigMap
      name: veritas-config
    patch: |-
      - op: replace
        path: /data/PUBLIC_IP
        value: "YOUR.EGRESS.IP.HERE"
      - op: replace
        path: /data/BROWSER_PROXY_HOST
        value: "YOUR.EGRESS.IP.HERE"
      - op: replace
        path: /data/BROWSER_EXPECTED_EGRESS_IP
        value: "YOUR.EGRESS.IP.HERE"
      - op: replace
        path: /data/STEALTH_ENDPOINT_HOST
        value: "YOUR.EGRESS.IP.HERE"
```

Confirm it renders cleanly before using it:

```sh
kubectl kustomize deploy/k8s/overlays/site | grep -c REPLACE_ME   # must print 0
```

## Deploying

```sh
kubectl -n veritas port-forward svc/registry 31500:5000 &
REGISTRY=localhost:31500 TAG=… bash deploy/k8s/scripts/push-images.sh
# record the new digests in overlays/k3s/kustomization.yaml, commit, push
bash deploy/k8s/scripts/apply.sh site
```

Digests still belong in the committed `overlays/k3s`; only the addresses are
site-local. That keeps the deployed image set reviewable in git.

## If the egress IP changes

Update `overlays/site`, re-apply, and restart the workloads that read these
values (`wg-manager`, `veritas-proxy`). Clients pick up the new endpoint on
their next config fetch.
