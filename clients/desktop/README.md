# VeritasVPN desktop (Tauri)

Linux and macOS client. Stealth TLS (wstunnel) and the firewall kill switch are **Linux-only**.

## Dev

```bash
cd clients/desktop
npm install
npm run tauri dev
```

## Release build

```bash
# Linux: refresh bundled engines first
./scripts/bundle-wg-linux.sh
./scripts/bundle-wstunnel-linux.sh

npm run tauri build
```

macOS: run `./scripts/bundle-wg-macos.sh` before build. Stealth is disabled in the UI on non-Linux.

## Stealth notes

- Settings → **Stealth** (Linux): Auto, UDP only, or Stealth always. Requires server `stealth_available` + bundled `src-tauri/resources/bin/wstunnel`.
- Change Exclude LAN or Stealth while connected → banner **Reconnect from Home to apply these changes**.
- Connected home shows **Direct UDP**, **Stealth**, or **Switching to Stealth…**.
- Home connect control is the lock circle. There is no separate Connect or Disconnect button and no exposure diagram.
- Settings → **Veritas Shield** has four Premium DNS toggles: malicious sites, trackers, ads, and adult sites. Copy stays DNS-only.
- Kill switch is always on while connected (firewall + fail-closed routes; no in-app off toggle). Connect aborts if the firewall ruleset cannot be installed.
- Auto-reconnect is always on (no off option).

See `src-tauri/resources/README.md` for binary paths.
