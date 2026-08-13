# emergency-disconnect-macos.sh

## For humans

Run this if VeritasVPN Connect kills your internet and Disconnect does nothing:

```bash
sudo bash clients/desktop/scripts/emergency-disconnect-macos.sh
```

It removes the WireGuard split-default routes, endpoint host route, DNS overrides, and `wireguard-go`.

## For AI

- Complements `lib.rs` teardown; use when elevated osascript disconnect failed or the Mac was rebooted mid-tunnel.
- Root cause of blackhole: full-tunnel `0.0.0.0/1` + `128.0.0.0/1` without a host route to the WG endpoint via the original gateway.
