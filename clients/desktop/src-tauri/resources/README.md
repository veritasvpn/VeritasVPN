# Bundled binaries (desktop)

## WireGuard (macOS + Linux)

The app ships a **userspace WireGuard** binary (`wireguard-go`) so customers only install VeritasVPN.

Rebuild (developers / CI):

```bash
./clients/desktop/scripts/bundle-wg-macos.sh   # macOS
./clients/desktop/scripts/bundle-wg-linux.sh   # Linux
```

## Stealth / wstunnel (Linux only)

Stealth mode wraps WireGuard in a TLS WebSocket via **wstunnel**. The Linux desktop client expects:

- Path: `resources/bin/wstunnel` (x86_64 ELF)
- Bundled via `bundle.resources` in `tauri.conf.json` (`resources/bin/**/*`)
- Runtime: `lib.rs` starts `wstunnel client` when `stealth_endpoint` is set

Fetch / refresh the binary before a Linux release build:

```bash
./clients/desktop/scripts/bundle-wstunnel-linux.sh
```

macOS builds reject stealth at connect time; the UI marks Stealth as **Linux only**.

## Useful information (AI)

- `resources/bin/wireguard-go` — userspace WG
- `resources/bin/wstunnel` — Linux stealth sidecar (omit or leave unused on macOS)
- Before installing full-tunnel routes, bring-up **must** add a host route for the WG/stealth endpoint via the original default gateway
- Teardown must never use `set -e` so cleanup always finishes
- Do not tell end users to `brew install wireguard-tools`
