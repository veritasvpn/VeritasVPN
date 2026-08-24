#!/usr/bin/env bash
# Install host tc shaping (bandwidth caps + uplink qdisc) from the repo in one step.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run as root: sudo $0" >&2
  exit 1
fi

install -m 0755 "$ROOT/deploy/node/veritas-bandwidth.sh" /usr/local/sbin/veritas-bandwidth.sh
install -m 0644 "$ROOT/deploy/systemd/veritas-bandwidth.service" /etc/systemd/system/veritas-bandwidth.service
install -m 0644 "$ROOT/deploy/systemd/veritas-bandwidth.timer" /etc/systemd/system/veritas-bandwidth.timer
install -m 0644 "$ROOT/deploy/systemd/veritas-qdisc.service" /etc/systemd/system/veritas-qdisc.service

systemctl daemon-reload
systemctl enable --now veritas-bandwidth.timer
systemctl enable --now veritas-qdisc.service

echo "Host shaping installed from $ROOT (veritas-bandwidth + veritas-qdisc)."
