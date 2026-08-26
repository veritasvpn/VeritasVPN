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

- Settings → **Stealth mode** (Linux). Requires server `stealth_available` + bundled `src-tauri/resources/bin/wstunnel`.
- Toggle Exclude LAN / Stealth while connected → banner **Reconnect to apply**.
- Connected badge shows **Direct UDP** or **Stealth TLS**.
- Kill switch is always on for the whole Linux session while connected (firewall + fail-closed routes; no off option). Connect aborts if the firewall ruleset cannot be installed.

See `src-tauri/resources/README.md` for binary paths.
