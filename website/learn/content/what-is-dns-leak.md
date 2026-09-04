---
title: What is a DNS leak?
description: When your VPN tunnel is up but DNS queries still go to another resolver—and how to catch it.
category: threats
slug: what-is-dns-leak
related: [what-is-dns, what-is-protected-dns, what-is-webrtc-leak]
updated: 2026-09-04
lede: A DNS leak happens when name lookups bypass your intended resolver—often still hitting your ISP—even though other traffic uses the VPN.
---

## The mismatch

You connect a VPN expecting privacy. Web pages load through the tunnel, but your device keeps asking your **ISP’s resolver** (or a browser DoH service) for hostnames. Those parties still learn much of your destination map.

That split is a **DNS leak**.

## Common causes

- OS or browser still using DHCP DNS instead of the VPN’s resolver
- **Smart DNS** or “media unblocker” features that intentionally split DNS
- Apps using their own [DNS-over-HTTPS](/learn/what-is-dns.html) endpoints
- Misconfigured split tunnel or kill switch gaps

## How to detect it

Use an independent check that shows which resolvers answered—VeritasVPN’s [DNS leak test](/check/dns.html) is built for that. Compare resolvers while disconnected vs connected.

## How serious is it?

Severity depends on threat model. Against a café attacker, tunnel encryption may still help. Against an ISP or resolver that logs queries, a leak undoes a large part of the privacy you thought you bought.

## Mitigation

- Prefer VPNs that **force** tunnel DNS and block obvious bypasses
- Disable conflicting “secure DNS” browser settings when testing
- Re-test after OS updates

VeritasVPN’s gateway is always on for connected WireGuard peers, with blocks for plain DNS, DoT, and well-known public DoH resolvers. Uncommon custom DoH can still residual-bypass—honesty beats false certainty.
