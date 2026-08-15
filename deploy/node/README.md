## Per-device bandwidth cap

The veritas-bandwidth service and its 15-second timer apply an independent 50 Mbps ceiling to every configured WireGuard peer. Download traffic is shaped with an HTB class and fq_codel leaf on wg0; upload traffic is policed by the peer's /32 source address. The reconciler only rebuilds queues when the peer set changes, so active tunnels are not interrupted on ordinary timer runs.

The cap is controlled by VERITAS_DEVICE_RATE in the service environment and defaults to 50mbit. The runtime installer is:

sudo install -m 0755 deploy/node/veritas-bandwidth.sh /usr/local/sbin/veritas-bandwidth.sh
sudo install -m 0644 deploy/systemd/veritas-bandwidth.service /etc/systemd/system/veritas-bandwidth.service
sudo install -m 0644 deploy/systemd/veritas-bandwidth.timer /etc/systemd/system/veritas-bandwidth.timer
sudo systemctl daemon-reload
sudo systemctl enable --now veritas-bandwidth.timer
