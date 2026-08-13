#!/usr/bin/env bash
set -euo pipefail

echo "=== STEP 4: Install k3s ==="
echo ""

confirm() { read -rp "$1 [y/N] " yn; if [ "$yn" != "y" ] && [ "$yn" != "Y" ]; then echo "aborted"; exit 0; fi; }

if command -v k3s &>/dev/null || systemctl is-active k3s &>/dev/null 2>&1; then
  echo "k3s appears to be already installed."
  kubectl get nodes 2>/dev/null
  confirm "k3s already installed. Skip installation?"
fi

confirm "Install k3s on this node?"

echo "Installing k3s..."
curl -sfL https://get.k3s.io | sh -s - \
  --write-kubeconfig-mode 644 \
  --disable servicelb \
  --disable traefik

echo "Waiting for k3s to be ready..."
sleep 10
kubectl wait --for=condition=ready node --all --timeout=120s
kubectl get nodes

echo "Setting up kubeconfig for the current user..."
mkdir -p ~/.kube
cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
chmod 600 ~/.kube/config

echo "Installing ingress-nginx controller..."
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/baremetal/deploy.yaml

echo "Waiting for ingress-nginx..."
kubectl wait --namespace ingress-nginx --for=condition=ready pod --selector=app.kubernetes.io/component=controller --timeout=120s

echo "Labeling node for veritas-agent..."
NODE_NAME=$(kubectl get nodes -o jsonpath='{.items[0].metadata.name}')
kubectl label node "$NODE_NAME" veritas-vpn-node=true --overwrite

echo ""
echo "[k3s] Done. Cluster ready:"
kubectl get nodes
