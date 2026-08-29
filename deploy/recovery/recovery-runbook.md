# VeritasVPN Dell/K3s disaster-recovery runbook

This restores the single production Dell after system-disk loss. It does not use the retired Raspberry Pi or Docker Compose deployment.

## Recovery targets

- RPO: 24 hours (daily encrypted backup).
- RTO: 4 hours after replacement hardware and router access are available.
- Canonical source: `master` in `veritasvpn/VeritasVPN`.
- Canonical deployment: `deploy/k8s/overlays/k3s` on the Dell OptiPlex.

## Required material

- A recent `veritasvpn-*.tar.gz.enc` archive and its `.hmac` and `.sha256` sidecars.
- The separately stored `/root/.config/veritasvpn/backup.key`.
- Router access for UDP 51820 and stealth TCP 443 forwarding.
- Cloudflare, Tailscale, GitHub, DNS, and Android-signing access.

Never paste credentials into this repository or a recovery ticket.

## 1. Rebuild the host

1. Install a supported Ubuntu Server LTS x86-64 image on the Dell.
2. Apply firmware and OS security updates; enable Secure Boot when supported.
3. Create the `jpg` administrator, verify SSH-key access and Tailscale, then disable password SSH.
4. Restore the reserved LAN address and forward public ports only to this Dell.

```sh
sudo apt-get update
sudo apt-get install -y curl git jq nftables wireguard-tools dnsutils openssl auditd audispd-plugins
curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION='v1.36.3+k3s1' sh -s - server
```

Use the K3s version recorded in the most recent recovery record if it differs.

## 2. Restore source and authenticate the backup

```sh
git clone https://github.com/veritasvpn/VeritasVPN.git /home/jpg/VeritasVPN
cd /home/jpg/VeritasVPN
git checkout master
git status --short
sudo BACKUP_ROOT=/path/to/recovery-backup KEY_FILE=/root/.config/veritasvpn/backup.key \
  ./deploy/backup/verify-backup.sh
```

Decrypt only after the HMAC and archive checks pass:

```sh
sudo install -d -m 700 /var/lib/veritasvpn/recovery
sudo openssl enc -d -aes-256-cbc -pbkdf2 \
  -pass file:/root/.config/veritasvpn/backup.key \
  -in /path/to/recovery-backup/veritasvpn-YYYYMMDDTHHMMSSZ.tar.gz.enc \
  | sudo tar -xzf - -C /var/lib/veritasvpn/recovery
```

## 3. Restore host VPN state and hardening

```sh
sudo install -d -m 700 /home/jpg/VeritasVPN/data/wireguard
sudo install -m 600 /var/lib/veritasvpn/recovery/wireguard-private.key \
  /home/jpg/VeritasVPN/data/wireguard/private.key
sudo ./deploy/node/bootstrap-wg.sh
sudo ./deploy/node/persist-rules.sh
sudo ./deploy/security/install-host-auditing.sh
```

Restore the audited K3s configuration with `write-kubeconfig-mode: "0600"` and Secret encryption enabled before application Secrets are loaded. Restart K3s and require `sudo k3s secrets-encrypt status` to report enabled.

## 4. Restore Secrets, workloads, and databases

The encrypted archive contains `veritas-k8s.yaml` and `btcpay-k8s.yaml`. Review them as root and apply only the required Secret and ConfigMap objects; never copy values into Git.

```sh
sudo kubectl apply -f /var/lib/veritasvpn/recovery/veritas-k8s.yaml
sudo kubectl apply -f /var/lib/veritasvpn/recovery/btcpay-k8s.yaml
sudo kubectl apply -k deploy/k8s/btcpay-mainnet
sudo kubectl apply -k deploy/k8s/monitoring
sudo ./deploy/k8s/scripts/apply.sh k3s
```

After both PostgreSQL pods are ready:

```sh
sudo gzip -dc /var/lib/veritasvpn/recovery/veritas.sql.gz \
  | sudo kubectl -n veritas exec -i postgres-0 -- psql -U veritas
sudo gzip -dc /var/lib/veritasvpn/recovery/btcpay.sql.gz \
  | sudo kubectl -n btcpay-mainnet exec -i postgres-btcpay-mainnet-0 -- psql -U btcpay
sudo ./deploy/k8s/scripts/apply.sh k3s
```

## 5. Restore public routing

1. Update the `k3s` overlay if the Dell public IPv4 changed.
2. Forward UDP 51820 and TCP 443 to the Dell only. Do not expose TCP 41080
   unless the Chrome gateway has separately passed its public-edge security review.
3. Restore the Cloudflare Tunnel Secret and verify the API, analytics, and mainnet BTCPay routes.
4. Keep all management ports private to LAN/Tailscale.

## 6. Acceptance and closure

```sh
sudo ./deploy/verify/boot-verify.sh
sudo ./deploy/backup/backup-k3s.sh
sudo ./deploy/backup/restore-test.sh
```

Trigger `.github/workflows/vpn-e2e.yml` and require authentication, peer provisioning, external WireGuard handshake, VPN DNS, HTTPS egress, revocation, and normal-network restoration to pass. Test the Chrome proxy with a Premium synthetic account before enabling its public download.

Rotate exposed credentials, securely delete `/var/lib/veritasvpn/recovery`, and record the K3s version, source commit, restored backup timestamp, public IP, achieved RPO/RTO, and test evidence. Confirm backup, restore rehearsal, VPN E2E, API, disk, TLS, and Alertmanager alarms are green. Validate the Chrome proxy only when that client is enabled.
