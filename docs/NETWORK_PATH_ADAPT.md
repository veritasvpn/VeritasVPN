# Network path adapt (Wi‑Fi ↔ cellular / gateway change)

## Product rule (Android)

After the user taps **Connect now**, the session must stay up until they tap
**Disconnect** (or the system revokes VPN permission). The app must **not**
auto-disconnect, delete the peer, or flip the UI to “Connecting…” on underlay
flaps, sticky service restarts, or transient tunnel DOWN events.

## Android (`VeritasVpnService` / `MainActivity`) — 0.2.25+

1. **No underlay NetworkCallback path-adapt.** WireGuard `PersistentKeepalive = 25` handles roaming. Previous path-adapt (DOWN→UP, then pinned `setUnderlyingNetworks`) caused connect loops.
2. On connect/restore: `setUnderlyingNetworks(null)` once (dynamic underlay only).
3. While `KEY_CONFIG` is saved (session intended): never clear it except **Disconnect** / `onRevoke`. Unexpected DOWN → keep UI connected + sticky/`START_STICKY` restore loop.
4. MainActivity ignores unintended `ACTION_STATE` disconnect broadcasts after a session is established. No automatic peer-delete reconnect.

## Linux desktop (`refresh_endpoint_route`)

1. Stats poll invokes `refresh_endpoint_route` while connected.
2. Reads `~/.veritasvpn/iface.meta` (`endpoint_ip`, `gateway`, `iface`).
3. Detects the current **unicast underlay** default gateway (skips blackhole kill-switch + tunnel iface).
4. If the gateway changed, elevates `refresh-route.sh` to `ip route replace $ENDPOINT via $NEW_GW` and updates meta.
5. Passwordless sudo includes `refresh-route.sh` (rewritten on each successful connect).

## Chrome extension

No WireGuard host route; TCP to the proxy reopens on the new path. No change required for network switching.

## Manual smoke

1. Connect on Wi‑Fi.
2. Leave the session alone for several minutes — UI must stay Connected; VPN icon must stay.
3. Switch Wi‑Fi ↔ cellular without tapping Disconnect — stay Connected (browsing may pause briefly while WireGuard roams).
4. Only **Disconnect** clears the VPN icon / session.
