---
title: What is protected DNS?
description: Filtering and encrypting DNS inside a VPN so malware and phishing names fail closed while you are connected.
category: protect
slug: what-is-protected-dns
related: [what-is-veritas-shield, what-is-dns, what-is-dns-leak, vpn-logging-explained]
updated: 2026-09-04
lede: Protected DNS means your VPN resolves names through a controlled gateway—often with threat feeds—so lookups are not left to a random café resolver.
---

## Pieces of the feature

1. **Force DNS into the tunnel** so the ISP’s resolver is not the default path
2. **Encrypt upstream** (DoH/DoT) between the gateway and recursive resolvers
3. **Block known-bad names** (malware, phishing) with NXDOMAIN or sinkholes
4. **Reduce bypasses** — drop plain DNS/DoT and well-known public DoH targets from peers

## What it is not

It is not a substitute for browser security updates or common sense. It does not see inside HTTPS bodies. Custom app DoH to unknown endpoints can still residual-bypass curated blocks.

## How to verify

Disconnect vs connect and run a [DNS leak test](/check/dns.html). Resolvers should change to the provider path when the tunnel is healthy.

## VeritasVPN

On VeritasVPN this capability is productized as **[Veritas Shield](/learn/what-is-veritas-shield.html)**: categorized feeds, optional presets (Security / Standard / Aggressive), and honesty about upstream DoH visibility. While connected, Android and Linux peers use `10.0.0.1` as DNS.
