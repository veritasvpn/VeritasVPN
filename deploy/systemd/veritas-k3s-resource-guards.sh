#!/usr/bin/env bash
# Persist resource ceilings for K3s-managed add-ons and the ingress controller.
# K3s may reconcile its packaged add-ons after an upgrade, so this service runs
# after every K3s start and reapplies only these narrow resource/security fields.
set -euo pipefail

for _ in $(seq 1 90); do
  if kubectl get node >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
kubectl get node >/dev/null

kubectl -n kube-system patch deployment metrics-server --type=strategic --patch '
spec:
  template:
    spec:
      containers:
        - name: metrics-server
          resources:
            requests: {cpu: 100m, memory: 70Mi}
            limits: {cpu: 300m, memory: 256Mi}
'

kubectl -n kube-system patch deployment local-path-provisioner --type=strategic --patch '
spec:
  template:
    spec:
      containers:
        - name: local-path-provisioner
          resources:
            requests: {cpu: 50m, memory: 64Mi}
            limits: {cpu: 250m, memory: 256Mi}
          securityContext:
            allowPrivilegeEscalation: false
            capabilities: {drop: ["ALL"]}
            seccompProfile: {type: RuntimeDefault}
'

kubectl -n ingress-nginx patch deployment ingress-nginx-controller --type=strategic --patch '
spec:
  template:
    spec:
      containers:
        - name: controller
          resources:
            requests: {cpu: 100m, memory: 90Mi}
            limits: {cpu: 500m, memory: 512Mi}
'
