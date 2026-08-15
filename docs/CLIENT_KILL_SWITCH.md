# Linux desktop kill switch

The Linux desktop WireGuard client uses fail-closed routing plus a dedicated nftables or iptables ruleset while connected.

## Behavior

- After the WireGuard handshake succeeds and the two tunnel /1 routes are installed, the client adds a dedicated blackhole default metric 1 route.
- The tunnel /1 routes are more specific, so connected traffic still uses WireGuard.
- If the tunnel interface or its routes disappear unexpectedly, traffic cannot fall back to the normal gateway; it is discarded by the blackhole route.
- An intentional Disconnect removes only the Veritas kill-switch route before restoring the normal network.
- A new connection removes a stale Veritas kill-switch route left by an interrupted session before rebuilding the tunnel.
- If the kill-switch route cannot be installed, bring-up aborts and leaves the previous network route unchanged.
- If firewall tooling is unavailable, the blackhole route remains as a safety fallback and the session is never silently treated as fully firewall-enforced.

## Scope

This protects Linux desktop traffic managed by the Tauri client. The Dell OptiPlex is the VPN server, so installing a route there does not protect a user's device. Android and Chrome have their own platform-specific behavior described below.

## Recovery

If a client is intentionally disconnected, the normal network is restored by the app. If the app is terminated unexpectedly, run the app's Disconnect action after reopening it. As a last resort, an administrator can remove the dedicated route with:

    sudo ip route del blackhole default metric 1

Only remove this route when the VPN is intentionally disconnected; removing it while the tunnel is down re-enables normal egress.

## Android

The Android client uses a full-tunnel VpnService, persists the approved tunnel configuration for system restarts, and cleans up when Android revokes VPN permission. For a system-level kill switch, enable Always-on VPN and Block connections without VPN in Android VPN settings; the app now opens those settings from its account menu. Without the Android system block setting, Android may restore normal networking after a service failure.

## Chrome extension

The extension can only protect Chrome traffic. If the authenticated proxy reports an error, it installs a local discard proxy and shows BROWSER TRAFFIC BLOCKED instead of falling back to a direct browser connection. Other applications on the device are outside its scope.
