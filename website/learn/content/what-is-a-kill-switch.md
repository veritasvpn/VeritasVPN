---
title: What is a VPN kill switch?
description: Fail-closed networking that blocks cleartext paths when the tunnel drops unexpectedly.
category: protect
slug: what-is-a-kill-switch
related: [how-does-a-vpn-work, what-is-split-tunneling, what-is-webrtc-leak]
updated: 2026-09-04
lede: A kill switch stops traffic from leaving your device outside the VPN when the tunnel fails—so a disconnect does not silently expose you.
---

## The failure mode it prevents

VPNs can drop: network change, sleep/wake, server hiccup, crashed process. Without a kill switch, the OS may instantly route traffic over the clear default gateway while you still believe you are “protected.”

## What “kill switch” means in practice

Implementations vary:

- **Firewall rules** that allow only tunnel and VPN endpoint traffic while “connected”
- **Routing tricks** that blackhole non-tunnel traffic
- **OS VPN always-on + block without VPN** (Android)

The trustworthy ones are **fail-closed**: prefer no internet over accidental exposure.

## Limits

- Misconfigured switches can break captive portals or LAN access
- [Split tunneling](/learn/what-is-split-tunneling.html) intentionally punches holes
- Browser-only proxies do not equal a system kill switch

## On VeritasVPN clients

Linux desktop uses firewall-oriented fail-closed behavior while connected (always on; no off option). Android uses a full-device WireGuard tunnel while connected (kill switch always on; no in-app off option). Auto-reconnect is always on for both clients.
