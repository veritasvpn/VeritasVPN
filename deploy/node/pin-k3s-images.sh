#!/usr/bin/env bash
set -euo pipefail

kubectl -n kube-system set image deployment/coredns \
  coredns=rancher/mirrored-coredns-coredns@sha256:900f9c109f7a33545d3c811516e8376df9019147b750f5ce3e254468769176ea
kubectl -n kube-system set image deployment/local-path-provisioner \
  local-path-provisioner=rancher/local-path-provisioner@sha256:1eba82e9c386038b4af6d69cca7519fac738c28c42735ed48ce70c882ad0d80f
kubectl -n kube-system set image deployment/metrics-server \
  metrics-server=rancher/mirrored-metrics-server@sha256:d9862115e7c7881280d3d75ca26bda8ffc0fc213315979575bf23ce9826205c0
