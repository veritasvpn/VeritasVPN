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

Hard reconnect (new peer / full bring-up) remains the fallback when the tunnel
interface is actually gone.

## Android (`VeritasVpnService`)

1. Register a `NetworkRequest` with `INTERNET` + **`NOT_VPN`** (never `registerDefaultNetworkCallback` — VPN bring-up looks like a new default network and caused a connect/disconnect loop in 0.2.21).
2. Fingerprint underlay transports only (wifi/cell/eth/bt) — never `TRANSPORT_VPN`.
3. After each successful UP, ignore underlay callbacks for **`PATH_ADAPT_GRACE_MS` (10s)**.
4. On a real underlay change: debounce, then soft bounce `DOWN→UP` with the same config (`softAdapting` suppresses reconnect broadcasts).
5. Soft-adapt failures request at most one full reconnect (gated), not a storm.

Shipped in **0.2.22+** (0.2.21 had the loop bug).

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
3. Within a few seconds, browsing should recover with the VPN still on.
4. Android status bar VPN icon should stay present (soft bounce, not a full session tear-down).
