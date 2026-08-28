#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-veritas}"
TIMEOUT="${TIMEOUT:-180s}"

for resource in \
  deployment/auth-svc \
  deployment/billing-svc \
  deployment/nginx \
  deployment/redis \
  deployment/registry \
  deployment/telegram-notifier \
  deployment/veritas-proxy \
  deployment/wg-manager \
  statefulset/nats \
  statefulset/postgres; do
  kubectl -n "$NAMESPACE" rollout status "$resource" --timeout="$TIMEOUT"
done

for daemonset in veritas-agent veritas-wstunnel; do
  desired="$(kubectl -n "$NAMESPACE" get daemonset "$daemonset" -o jsonpath='{.status.desiredNumberScheduled}')"
  ready="$(kubectl -n "$NAMESPACE" get daemonset "$daemonset" -o jsonpath='{.status.numberReady}')"
  desired_image="$(kubectl -n "$NAMESPACE" get daemonset "$daemonset" -o jsonpath='{.spec.template.spec.containers[0].image}')"
  running_image="$(kubectl -n "$NAMESPACE" get pods -l "app=$daemonset" -o json \
    | jq -r '.items[] | select(.metadata.deletionTimestamp == null) | .spec.containers[0].image' \
    | head -1)"
  if [[ "$desired" != "0" && "$ready" == "$desired" && "$running_image" == "$desired_image" ]]; then
    printf '[OK]   daemonset/%s ready on desired image\n' "$daemonset"
  else
    printf '[FAIL] daemonset/%s is not ready on its desired image\n' "$daemonset" >&2
    exit 1
  fi
done

if kubectl -n "$NAMESPACE" get pods -o json | jq -e '
  [.items[]
    | select(.metadata.deletionTimestamp == null)
    | select(
        .status.phase != "Running"
        or (.status.containerStatuses | length) == 0
        or any(.status.containerStatuses[]; .ready != true)
      )
  ] | length == 0' >/dev/null; then
  printf '[OK]   all %s pods are ready and running\n' "$NAMESPACE"
else
  printf '[FAIL] one or more %s pods are not ready and running\n' "$NAMESPACE" >&2
  kubectl -n "$NAMESPACE" get pods -o wide >&2
  exit 1
fi

curl --fail --silent --show-error --max-time 3 http://127.0.0.1:9090/healthz >/dev/null
printf '[OK]   agent health endpoint responds\n'

if ! command -v dig >/dev/null 2>&1; then
  printf '[FAIL] dig is required for VPN DNS validation\n' >&2
  exit 1
fi
dig +short +time=3 +tries=1 @10.0.0.1 api.veritasvpn.cloud A \
  | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$'
printf '[OK]   VPN DNS resolves through 10.0.0.1\n'

kubectl -n "$NAMESPACE" exec daemonset/veritas-agent -- wg show wg0 >/dev/null
printf '[OK]   WireGuard interface is available\n'

if timeout 3 bash -c 'echo >/dev/tcp/127.0.0.1/443' 2>/dev/null; then
  printf '[OK]   stealth listener is available on TCP 443\n'
else
  printf '[FAIL] stealth listener is unavailable on TCP 443\n' >&2
  exit 1
fi

curl --fail --silent --show-error --max-time 10 https://api.veritasvpn.cloud/healthz >/dev/null
printf '[OK]   public API health endpoint responds\n'
printf 'Core deployment verification: PASS\n'
