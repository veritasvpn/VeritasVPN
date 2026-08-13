# Bundled WireGuard binaries (desktop)

## Useful information (humans)

The macOS app ships a **userspace WireGuard** binary (`wireguard-go`) so customers only install VeritasVPN — no Homebrew / WireGuard.app required.

Rebuild (developers / CI):

```bash
./clients/desktop/scripts/bundle-wg-macos.sh
```

## Useful information (AI)

- Path: `resources/bin/wireguard-go` (arm64 Mach-O)
- Tauri bundles via `bundle.resources` in `tauri.conf.json`
- Runtime: `lib.rs` starts this binary with admin privileges, configures via UAPI + `ifconfig`/`route`
- Before installing full-tunnel `0.0.0.0/1` + `128.0.0.0/1` routes, bring-up **must** add a host route for the WG endpoint via the original default gateway (otherwise WireGuard UDP is blackholed and internet dies).
- Teardown persists endpoint/gateway/DNS service in `iface.meta` and must never use `set -e` so cleanup always finishes.
- Do not tell end users to `brew install wireguard-tools`
