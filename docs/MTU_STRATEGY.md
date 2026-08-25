# WireGuard MTU strategy

## Product defaults (intentional)

| Role | Interface / config | MTU | Notes |
|------|--------------------|-----|-------|
| Server | `wg0` on the VPN node | **1420** | Matches typical Ethernet + WireGuard overhead on the node |
| Clients | Desktop / Android issued configs & runtime | **1280** | Safe default for cellular / public Wi‑Fi / hostile paths |
| Server TCP path | nftables / agent MSS clamp | **~1380** | Remains as-is; complements client MTU for TCP |

These values are the **product default**, not a misconfiguration.

## Why clients stay at 1280

VeritasVPN prefers **reliability on mobile and hostile paths** over peak efficiency:

- Cellular and many public Wi‑Fi paths add encapsulation or lower path MTU; a high client MTU causes fragmentation or black-hole failures.
- **1280** is a conservative, widely safe tunnel MTU for issued clients.
- Server **1420** fits the node’s Ethernet/WG overhead; with a ~**100 Mbps** per-device cap, asymmetric MTU is an acceptable tradeoff.
- Do **not** “fix” connectivity by blindly raising client MTU without path MTU testing on the networks users actually use.

## Where this is set in code / ops

- **Desktop**: `clients/desktop/src-tauri/src/lib.rs` — sets interface MTU to `1280` on connect (macOS / Linux bring-up scripts).
- **Android**: issued config `MTU = 1280` in `MainActivity.kt`; tun reports `1280` in `android/wireguard/wgbackend.go`.
- **Server `wg0`**: live node MTU is **1420** (WireGuard/Linux default for the interface; bootstrap does not override it).
- **MSS clamp ~1380**: `deploy/firewall/nftables.conf` and `services/veritas-agent/internal/firewall/nftables.go`.

## Ops guidance

If you change MTU, document the path tests (cellular, captive Wi‑Fi, home Ethernet) and update this file plus the client defaults together. Raising only the client or only the server without testing is not a supported “optimization.”
