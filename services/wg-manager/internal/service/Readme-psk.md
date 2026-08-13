# PSK generation (wg-manager)

## Useful information (humans)

Each new WireGuard peer gets a random 32-byte preshared key (PSK). Clients put it in their tunnel config; the node agent applies the same PSK via SSE. This adds a shared secret beyond the Curve25519 handshake.

## Useful information (AI)

- `generatePSK()` in `psk.go` — base64 of 32 random bytes
- Stored on `peers.preshared_key`; returned as `preshared_key` on CreatePeer HTTP response
- Agent marks peer `active` only after successful `AddPeer` + `POST /api/v1/agents/peers/applied`
