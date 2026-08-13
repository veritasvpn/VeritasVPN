#!/bin/bash
# Usage: add-peer.sh <name> [next_ip]
# Generates a WireGuard client config and adds the peer to this gateway.
# The client config is printed to stdout and saved as data/clients/<name>.conf

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="$SCRIPT_DIR/data"
CLIENTS_DIR="$DATA_DIR/clients"
PEERS_FILE="$DATA_DIR/peers.conf"
SERVER_PUBLIC_KEY_FILE="$DATA_DIR/server_public.key"

mkdir -p "$CLIENTS_DIR" "$DATA_DIR"

NAME="${1:-client}"
NEXT_IP="${2}"

if [ ! -f "$SERVER_PUBLIC_KEY_FILE" ]; then
    echo "Error: Server not initialized. Start the container first." >&2
    exit 1
fi

SERVER_PUB=$(cat "$SERVER_PUBLIC_KEY_FILE")

# Determine next available IP if not specified
if [ -z "$NEXT_IP" ]; then
    USED_IPS=$(grep -oP '10\.10\.0\.\K\d+' "$PEERS_FILE" 2>/dev/null || echo "")
    MAX_USED=1  # server is .1
    for ip in $USED_IPS; do
        if [ "$ip" -gt "$MAX_USED" ]; then
            MAX_USED="$ip"
        fi
    done
    NEXT_IP=$((MAX_USED + 1))
fi

CLIENT_IP="10.10.0.${NEXT_IP}"

# Generate client keypair
CLIENT_PRIV=$(wg genkey)
CLIENT_PUB=$(echo "$CLIENT_PRIV" | wg pubkey)

# Determine public endpoint
PUBLIC_IP=${PUBLIC_IP:-$(curl -s ifconfig.me 2>/dev/null || echo "YOUR_SERVER_IP")}
ENDPOINT="${PUBLIC_IP}:51821"

# Append peer to peers file
cat >> "$PEERS_FILE" <<PEER

# Peer: $NAME
[Peer]
PublicKey = $CLIENT_PUB
AllowedIPs = ${CLIENT_IP}/32
PEER

echo "[veritas-proxy] Peer $NAME added with IP $CLIENT_IP" >&2

# Generate client config
cat <<CONF > "$CLIENTS_DIR/${NAME}.conf"
[Interface]
PrivateKey = $CLIENT_PRIV
Address = ${CLIENT_IP}/32
DNS = 1.1.1.1

[Peer]
PublicKey = $SERVER_PUB
Endpoint = $ENDPOINT
AllowedIPs = 10.10.0.0/24
PersistentKeepalive = 25
CONF

cat "$CLIENTS_DIR/${NAME}.conf"
