# Linux desktop kill switch

The Linux desktop WireGuard client uses fail-closed routing plus a dedicated nftables or iptables ruleset while connected.

## Behavior

- After the WireGuard handshake succeeds and the two tunnel /1 routes are installed, the client adds a dedicated blackhole default metric 1 route.
- The tunnel /1 routes are more specific, so connected traffic still uses WireGuard.
- If the tunnel interface or its routes disappear unexpectedly, traffic cannot fall back to the normal gateway; it is discarded by the blackhole route.
- nftables (preferred) or iptables firewall rules are then installed and are **mandatory**. If they cannot be installed, bring-up aborts and restores the previous network. There is no in-app off toggle.
- An intentional Disconnect removes only the Veritas kill-switch route and firewall table before restoring the normal network.
- A new connection removes a stale Veritas kill-switch route left by an interrupted session before rebuilding the tunnel.
- If the kill-switch route cannot be installed, bring-up aborts and leaves the previous network route unchanged.

## Scope

This protects Linux desktop traffic managed by the Tauri client. The production node is the VPN server, so installing a route there does not protect a user's device. Android and Chrome have their own platform-specific behavior described below.

## Recovery

If a client is intentionally disconnected, the normal network is restored by the app. If the app is terminated unexpectedly, run the app's Disconnect action after reopening it. As a last resort, an administrator can remove the dedicated route with:

    sudo ip route del blackhole default metric 1

Only remove this route when the VPN is intentionally disconnected; removing it while the tunnel is down re-enables normal egress.

## Android

The Android client uses a full-tunnel VpnService so connected app traffic is forced through WireGuard while the session is up. That tunnel does not fail closed by itself: if it drops, Android can send traffic to the clearnet unless system **Always-on VPN** and **Block connections without VPN** are enabled for VeritasVPN.

Connect is blocked until `VpnKillSwitch.isLockdownEnabled` is true (this package is the Always-on VPN app and lockdown is on). The app cannot flip those OS settings. When Connect is blocked, the client explains both switches and opens system VPN settings (`Settings.ACTION_VPN_SETTINGS`). Returning to the app re-checks them; Connect continues only after both are detected. Cancel leaves the user disconnected. There is no in-app off toggle and no way to connect without both settings. Auto-reconnect stays always on for an established session (sticky restore + Always-on). Linux desktop behavior above and Chrome extension behavior below are unchanged.

## Chrome extension

The extension can only protect Chrome traffic. If the authenticated proxy reports an error, it installs a local discard proxy and shows BROWSER TRAFFIC BLOCKED instead of falling back to a direct browser connection. Other applications on the device are outside its scope.
