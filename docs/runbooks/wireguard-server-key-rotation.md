# WireGuard server key rotation

Rotate the key the veritas-agent uses for the WireGuard interface. Do this on the VPN node as root. Do not commit the old or new key, and do not paste either key into chat, tickets, or CI logs.

The agent reads `/etc/wireguard/private.key` (`WG_PRIVATE_KEY_FILE`). That directory is mounted into the `veritas-agent` DaemonSet. The DaemonSet uses `updateStrategy: OnDelete`, so a new file is picked up only when the pod is deleted. If the file exists but cannot be read or parsed, the agent logs an error and exits non-zero. It generates a key only when the file is actually missing.

## Before you start

Clients that are connected will drop when the pod restarts and will reconnect. Android 0.2.97 rebuilds its saved tunnel when a peer refresh or a new peer returns a different server public key, so it does not have to wait for a manual reconnect. Older Android builds keep the previous server key until the next manual connect and can sit on Reconnecting until then. The Linux app creates a new peer on each reconnect, so the next reconnect gets the new key.

The app does not change Direct or Stealth mode during the rotation. Reconnect keeps going until it succeeds.

## Steps

1. Back up the current key. Keep the backup on the node, mode `0600`, outside git.

   ```bash
   sudo install -d -m 0700 /root/wireguard-key-backups
   sudo cp -a /etc/wireguard/private.key \
     "/root/wireguard-key-backups/private.key.$(date -u +%Y%m%dT%H%M%SZ)"
   sudo chmod 600 /root/wireguard-key-backups/private.key.*
   ```

2. Generate the new key as root with a private umask, then install it over the live file.

   ```bash
   sudo install -d -m 0700 /etc/wireguard
   sudo sh -c 'umask 077; wg genkey > /etc/wireguard/private.key.new'
   sudo chmod 600 /etc/wireguard/private.key.new
   sudo chown root:root /etc/wireguard/private.key.new
   sudo mv /etc/wireguard/private.key.new /etc/wireguard/private.key
   ```

3. Note the public key locally. You will compare it with the database. Do not copy it into a ticket.

   ```bash
   sudo wg pubkey < /etc/wireguard/private.key
   ```

4. Delete the agent pod so OnDelete starts a new one with the new file.

   ```bash
   kubectl -n veritas delete pod -l app=veritas-agent
   kubectl -n veritas rollout status daemonset/veritas-agent
   ```

5. Confirm registration wrote `servers.public_key` for this hostname, and that it matches the public key from step 3.

   ```bash
   kubectl -n veritas exec deploy/postgres -- \
     psql -U veritas -d veritas -c \
     "SELECT hostname, public_key, updated_at FROM servers ORDER BY updated_at DESC LIMIT 5;"
   ```

   The agent log should not say it generated a key. Generation means the file was missing. An unreadable file exits instead of replacing the key.

6. From a phone or a Linux client, connect once with UDP and once with Stealth. Both should handshake. Leave each client on the mode it already used.

## Rollback

1. Restore the backup over `/etc/wireguard/private.key` (`chmod 600`, owner root).
2. Delete the agent pod again, the same OnDelete step as above.
3. Confirm `servers.public_key` matches the restored key (`sudo wg pubkey < /etc/wireguard/private.key`).
4. Connect with UDP and with Stealth.

## What people see

While the pod is down, connected clients lose the tunnel and show Reconnecting. The reconnect loop does not stop. After the new pod registers, a current Android client picks up the new server public key on the next peer read or peer create. A current Linux client picks it up on the next reconnect, which creates a new peer. A client that is older than Android 0.2.97 keeps the previous server key until the person connects again.
