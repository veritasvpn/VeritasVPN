---
title: What is split tunneling?
description: Sending some traffic through the VPN and some around it—why people want it and how it increases leak surface.
category: protect
slug: what-is-split-tunneling
related: [what-is-a-kill-switch, how-does-a-vpn-work, what-is-a-vpn]
updated: 2026-09-04
lede: Split tunneling lets chosen apps or destinations bypass the VPN for speed or local access—at the cost of a larger exposure surface.
---

## Why it exists

Full tunnel is simplest for privacy. Split tunnel helps when you need:

- Local printers and NAS on your LAN
- A banking app that blocks datacenter IPs
- Lower latency for a game while browsing privately

## Forms you will see

- **Exclude LAN** — private ranges stay local
- **Per-app bypass** — listed apps skip the tunnel (common on Android)
- **Inverse split** — only listed routes use the VPN (less common in consumer apps)

## Privacy cost

Every bypass is traffic that local networks and your ISP can see again. Combine split tunnel with a [kill switch](/learn/what-is-a-kill-switch.html) carefully—rules must agree on what is allowed.

## VeritasVPN

Android and Linux clients support excluding LAN and (on Android) per-app bypass. Reconnect after changing settings so routes match intent.
