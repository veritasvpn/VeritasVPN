# Phase 0 — Network switch repro matrix (Linux)

## Symptom (user report)

1. Connect while on network A.
2. Switch networks (Wi‑Fi ↔ cellular / LAN ↔ WAN).
3. UI stays Connected.
4. Clearnet browsing stops.
5. Manual Disconnect restores browsing.
6. Connect again works.

## Current soft path adapt (what already exists)

From `clients/desktop/src-tauri/src/lib.rs` + `clients/desktop/src/App.tsx`:

1. Stats poll every **1.5s** invokes `refresh_endpoint_route`.
2. That command only rewrites the **pinned endpoint host route** (`endpoint_ip` from `~/.veritasvpn/iface.meta`) when the underlay default gateway IP changes.
3. It does **not**:
   - watch link / route changes
   - reinstall kill switch after a path flap
   - re-apply DNS after a switch
   - soft-reconnect when the tunnel is unreachable

## Phase 0 goal

Reproduce the failure and classify which of the following is the dominant failure mode:

| Mode | What to look for | Likely symptom |
|------|------------------|----------------|
| **A. Stale endpoint host route** | `ip route get <endpoint_ip>` still via old gateway | WG UDP dies; kill switch blackholes clearnet |
| **B. Underlay gateway not detected** | `refresh` returns “underlay gateway not ready yet” | Soft path adapt no-ops; tunnel stays dead |
| **C. Kill switch still installed** | `nft list table inet veritasvpn_killswitch` still present | After soft failure, clearnet stays blackholed until Disconnect |
| **D. DNS stale** | `/etc/resolv.conf` still `10.0.0.1` or old nameserver after switch | Browsing fails even if WG recovers |
| **E. Handshake dead but UI Connected** | `wg show` has no handshake or handshake age high; UI still Connected | Soft recovery never runs |

## How to run

Use the **absolute path** if you are not in the repo root, or if you switch networks
while the shell is still in a different directory:

```bash
# Baseline (while still connected on network A)
bash /tmp/VeritasVPN-src/clients/desktop/scripts/network-switch-repro.sh baseline

# After switching networks (do NOT Disconnect)
bash /tmp/VeritasVPN-src/clients/desktop/scripts/network-switch-repro.sh after

# Optional: also dump a short report
bash /tmp/VeritasVPN-src/clients/desktop/scripts/network-switch-repro.sh after | tee /tmp/network-switch-report.txt
```

Reports land under `/tmp/veritas-repro/` by default
(`network-switch-repro-baseline.txt` / `...-after.txt`).

The harness is **fail-safe**: each network command is timed out and the report is
always written. If you previously saw “nothing”, re-run after the switch; you
should always get a file under `/tmp/veritas-repro/` with at least the header
and FLAG lines.

## Capture checklist

- UI: Connected or not
- `ip -4 route show default`
- `ip route get <endpoint_ip>`
- `cat ~/.veritasvpn/iface.meta`
- `nft list table inet veritasvpn_killswitch`
- `/etc/resolv.conf`
- public IP (ipify / ifconfig)

## Expected Phase 0 outcome

1. A written report of baseline vs after.
2. A confirmed failure mode (A–E) so Phase 1 can target the right fix.
3. A clear note whether soft path adapt is “skipping” when it should not.

## Phase 0 status (this session)

- Repro harness: `clients/desktop/scripts/network-switch-repro.sh`
- Docs: this file + updates to `docs/NETWORK_PATH_ADAPT.md`
- Code analysis of `refresh_endpoint_route_linux` + kill switch + stats:
  - Soft path adapt is **endpoint host route only**
  - Gateway detection is **fragile** when underlay is blackholed or missing
  - DNS is **not** re-applied after a switch
  - Kill switch remains active when the tunnel is dead
- Environment notes: this agent environment may not have full `ip`/route visibility; the harness is for the **user’s machine**.

## User baseline (from Linux PC)

From the first successful report after baseline on network A:

| Signal | Observation |
|--------|-------------|
| UI / tunnel | Process is `veritasvpn-desktop` only; **no** `wireguard-go` / empty `wg dump` |
| Underlay | Default via `wlx…` gw `192.168.0.1`; meta `gw_if=eno1` (stale) |
| Public egress | `api.ipify.org` / ifconfig / ipinfo all **FAIL** |
| Kill switch | No `nft` table `veritasvpn_killswitch` |
| Endpoint route | `ip route get` reaches endpoint host on underlay (soft path ok) |

**Interpretation:** even **before** the switch, the tunnel is not active (Mode **E**).
After switch without Disconnect, re-run `after` and check:

1. Report file exists under `/tmp/veritas-repro/` (not empty).
2. `wg dump` still empty / no handshake → tunnel still dead.
3. `FLAG: gateway_changed` → soft path adapt may have run (or not).
4. Kill switch still missing or blackhole default present → Mode **C**.

## Next phases

- Phase 1: link/route change watch
- Phase 2: soft recovery (endpoint + DNS + health)
- Phase 3: soft reconnect if soft fails
- Phase 4: UI safety
