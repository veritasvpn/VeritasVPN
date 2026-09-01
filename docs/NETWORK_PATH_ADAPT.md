# Network path adapt (Wi‑Fi ↔ cellular / gateway change)

## Problem

When a device stays “VPN connected” but the underlay changes (Wi‑Fi A → Wi‑Fi B,
Wi‑Fi → cellular, LAN → WAN), WireGuard’s UDP sockets and/or the desktop
endpoint host route can stay bound to the **old** path. Browsing dies until the
user manually disconnects and reconnects.

## Product rule

While the user intends to stay connected, clients must **soft-adapt** to underlay
changes automatically. Soft-adapt must **not** delete/recreate the server peer
and must **not** show a second permission dialog.

**Android status-bar VPN icon:** must stay visible for the whole intended session.
It may disappear only after the user taps **Disconnect** (or the system revokes
VPN permission). Network switches must not tear down `VpnService`.

Hard reconnect (new peer / full bring-up) remains the fallback when the tunnel
interface is actually gone *and* sticky restore cannot bring it back — not for
routine underlay changes.

## Android (`VeritasVpnService`)

1. Register a `NetworkRequest` with `INTERNET` + **`NOT_VPN`** (never `registerDefaultNetworkCallback` — VPN bring-up looks like a new default network and caused a connect/disconnect loop in 0.2.21).
2. Fingerprint underlay transports only (wifi/cell/eth/bt) — never `TRANSPORT_VPN`.
3. After each successful UP, ignore underlay callbacks for **`PATH_ADAPT_GRACE_MS` (10s)**.
4. On a real **transport** change (wifi↔cell only — not Network-object churn on the same Wi‑Fi): debounce, then call **`VpnService.setUnderlyingNetworks(null)`** so Android picks the default underlay dynamically. **Never** pin to a specific `Network` (that makes the system tear the VPN down when the Network object is later replaced — connect loop in 0.2.23). **Never** `backend.setState(DOWN)` for path adapt — GoBackend’s DOWN path `stopSelf()`s the service and drops the VPN icon.
5. On underlay `onLost`, clear the underlay pin with `setUnderlyingNetworks(null)`; leave the tunnel up.
6. Unexpected tunnel DOWN while a saved config remains: preserve config and let `START_STICKY` / Always-on restore — do **not** broadcast a UI disconnect that deletes the peer.

Shipped keep-alive dynamic rebind in **0.2.24+** (0.2.23 pinned a Network and looped; 0.2.22 bounced DOWN→UP).

## Linux desktop (`refresh_endpoint_route`)

1. Stats poll invokes `refresh_endpoint_route` while connected.
2. Reads `~/.veritasvpn/iface.meta` (`endpoint_ip`, `gateway`, `iface`).
3. Detects the current **unicast underlay** default gateway (skips blackhole kill-switch + tunnel iface).
4. If the gateway changed, elevates `refresh-route.sh` to `ip route replace $ENDPOINT via $NEW_GW` and updates meta.
5. Passwordless sudo includes `refresh-route.sh` (rewritten on each successful connect).

## Chrome extension

No WireGuard host route; TCP to the proxy reopens on the new path. No change required for network switching.

## Manual smoke

1. Connect on Wi‑Fi (same LAN as the node is fine).
2. Switch to cellular or another Wi‑Fi **without** opening the app’s Disconnect.
3. The Android status-bar VPN key/icon must **never** disappear during the switch.
4. Within a few seconds, browsing should recover with the app still showing connected.
5. Only after tapping **Disconnect** should the VPN icon go away.
