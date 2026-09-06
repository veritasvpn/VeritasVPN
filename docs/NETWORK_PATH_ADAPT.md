# Network path adapt (Wi‑Fi ↔ cellular / gateway change)

## Product rule (Android)

After the user taps **Connect now**, the session must stay up until they tap
**Disconnect** (or the system revokes VPN permission). The app must **not**
auto-disconnect, delete the peer, or flip the UI to “Connecting…” on underlay
flaps, sticky service restarts, or transient tunnel DOWN events.

## Android (`VeritasVpnService` / `MainActivity`) — 0.2.34+

**2-minute loop note:** WireGuard rekeys around 120s. Older builds treated handshake age > 120s as stale and called reconnect (or soft-adapted DOWN→UP). Never reconnect on handshake age; the Handshake “2m ago” UI label alone is not a failure.

1. **Underlay NetworkCallback path-adapt.** Watch `INTERNET` + `NOT_VPN` networks. Debounce ~1.2s. Do **not** delete the peer, flip the UI to Connecting, or reconnect on handshake age.
2. First callback after connect **records the underlay fingerprint only** and binds `setUnderlyingNetworks` to that validated non-VPN network. Do not swap the API endpoint (0.2.31 swapped WAN `:443` → LAN `:51820` on any `192.168.0.0/24` Wi‑Fi and blackholed browse).
3. Later callbacks, when the fingerprint **changes** (real A→B): bind the new underlay, persist the exact API LAN (`:51820`) or WAN (`:443`) into `KEY_CONFIG`, then rebind userspace WireGuard **without** `GoBackend.setState()`. Public `setState(UP)` internally DOWN→UPs and calls `VpnService.stopSelf()`, which destroys the service and drops the status-bar VPN key. Path-adapt instead `wgTurnOff` + `setStateInternal(UP)` so sockets follow the new path while this VpnService stays alive.
4. After rebound: `setUnderlyingNetworks(newNetwork)`, re-`protect()` WG sockets, keep stats polling, keep the foreground notification.
5. `sessionGeneration` increments on Connect and Disconnect. Path-adapt/restore abort if the generation changed. Real Disconnect is the only path that should `stopSelf()` (returns `START_NOT_STICKY`).
6. MainActivity ignores `EXTRA_CONNECTED=true` when `userWantsConnected` is false, and ignores unintended disconnect broadcasts after a session is established. No automatic peer-delete reconnect.

## Linux desktop (`refresh_endpoint_route`)

1. Soft path adapt runs in the **backend watcher only** (never from the UI stats
   poll — that froze the app with “veritasvpn is not responding”).
2. Reads `~/.veritasvpn/iface.meta` (`endpoint_ip`, `gateway`, `iface`).
3. Detects the current **unicast underlay** default gateway (skips blackhole kill-switch + tunnel iface).
4. If the gateway changed, elevates `refresh-route.sh` with **noninteractive**
   sudo only (`sudo -n`) to `ip route replace $ENDPOINT via $NEW_GW` and updates meta.
5. Soft recovery never falls back to interactive `pkexec` for path adapt.

## Phase 0 notes (network switch bug)

**User report:** Connect → change network → UI still Connected → no clearnet browse → Disconnect fixes → Connect works.
Also: Connect often showed “veritasvpn is not responding” (Cancel / Wait).

**What existed before Phase 1–3:**

- Endpoint host route is refreshed when the underlay gateway IP changes.
- No netlink / link-change watch.
- DNS is applied only at bring-up (not re-applied after a switch).
- Kill switch stays installed (nft/iptables + blackhole default) even if the tunnel is dead.
- Soft recovery does not force a reconnect when the tunnel is unreachable.

**How to capture Phase 0 evidence:**

```bash
bash clients/desktop/scripts/network-switch-repro.sh baseline
# ... switch networks ...
bash clients/desktop/scripts/network-switch-repro.sh after
```

See `docs/NETWORK_SWITCH_REPRO.md` for the failure-mode matrix (A–E).

## Phase 1–3 (network switch recovery)

Implemented on Linux desktop (0.2.38+):

1. **Phase 1 — underlay watch**
   - Background watcher polls every ~2s while a tunnel is up.
   - Detects underlay gateway / tunnel health changes.
2. **Phase 2 — soft recovery**
   - Endpoint host route refresh when underlay gateway changes (noninteractive sudo).
   - DNS re-applied from last-config after a switch.
   - Kill switch is cleaned if the tunnel is unhealthy so clearnet can recover.
   - If passwordless sudo is unavailable, kill-switch cleanup is spawned via
     **detached** `pkexec` (one auth prompt, UI stays responsive).
3. **Phase 3 — soft reconnect**
   - If tunnel is still dead after soft recovery, re-bring the tunnel up using
     `last-config.json` (saved on connect) without user Disconnect.
   - Soft reconnect is **detached**, throttled (~30s), and uses **soft-elevated**
     mode (noninteractive sudo only — never interactive pkexec).
4. **UI freeze fix**
   - Connect/disconnect run elevated work via `spawn_blocking` (async commands).
   - UI stats poll never calls soft recovery / path adapt.

### Files

- `clients/desktop/src-tauri/src/network_switch.rs` — recovery logic
- `clients/desktop/src-tauri/src/lib.rs` — watcher + soft reconnect + config save
- `clients/desktop/src/App.tsx` — stats poll only (no soft recovery invoke)

### Soft recovery commands

```bash
# Manual recovery (diagnostics)
bash clients/desktop/scripts/network-switch-repro.sh baseline
# switch networks without Disconnect
bash clients/desktop/scripts/network-switch-repro.sh after
```

## Chrome extension

No WireGuard host route; TCP to the proxy reopens on the new path. No change required for network switching.

## Manual smoke

1. Connect on Wi‑Fi.
2. Leave the session alone for several minutes — UI must stay Connected; VPN icon must stay.
3. Switch Wi‑Fi ↔ cellular without tapping Disconnect — stay Connected (browsing may pause briefly while WireGuard roams).
4. Only **Disconnect** clears the VPN icon / session.
