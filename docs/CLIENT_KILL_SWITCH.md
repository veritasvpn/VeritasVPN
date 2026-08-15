# Linux desktop kill switch

The Linux desktop WireGuard client uses a fail-closed route while connected.

## Behavior

- After the WireGuard handshake succeeds and the two tunnel /1 routes are installed, the client adds a dedicated blackhole default metric 1 route.
- The tunnel /1 routes are more specific, so connected traffic still uses WireGuard.
- If the tunnel interface or its routes disappear unexpectedly, traffic cannot fall back to the normal gateway; it is discarded by the blackhole route.
- An intentional Disconnect removes only the Veritas kill-switch route before restoring the normal network.
- A new connection removes a stale Veritas kill-switch route left by an interrupted session before rebuilding the tunnel.
- If the kill-switch route cannot be installed, bring-up aborts and leaves the previous network route unchanged.

## Scope

This protects Linux desktop traffic managed by the Tauri client. The Dell OptiPlex is the VPN server, so installing a route there does not protect a user's device. Android needs Android VpnService/Always-on VPN integration, macOS needs a NetworkExtension or carefully managed pf policy, and the Chrome extension can only fail closed inside the browser proxy scope.

## Recovery

If a client is intentionally disconnected, the normal network is restored by the app. If the app is terminated unexpectedly, run the app's Disconnect action after reopening it. As a last resort, an administrator can remove the dedicated route with:

    sudo ip route del blackhole default metric 1

Only remove this route when the VPN is intentionally disconnected; removing it while the tunnel is down re-enables normal egress.
