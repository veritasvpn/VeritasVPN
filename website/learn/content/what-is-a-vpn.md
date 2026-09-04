---
title: What is a VPN?
description: A plain-language explanation of virtual private networks, what they protect, and what they do not.
category: basics
slug: what-is-a-vpn
related: [how-does-a-vpn-work, vpn-vs-proxy, can-vpn-see-your-history]
updated: 2026-09-04
lede: A VPN encrypts your traffic between your device and a remote server so networks between you and that server cannot easily read or tamper with it.
---

## In one sentence

A **virtual private network (VPN)** creates an encrypted tunnel from your device to a server you choose. Apps and websites then see that server’s address instead of your home or café network’s public IP—while the path between you and the VPN is harder for others on the same Wi‑Fi or your ISP to inspect.

## What problem it solves

On an open network (home ISP, hotel Wi‑Fi, mobile data), many parties can observe **metadata** about your traffic: which IPs you talk to, when, and how much data you move. On insecure networks, attackers may also try to intercept unencrypted sessions.

A VPN does not make you invisible. It **moves the trust point**: instead of trusting every hop from your laptop to the internet, you trust the VPN provider’s exit for the duration of the session.

## What a VPN typically changes

- **Encryption on the first hop** — traffic to the VPN server is wrapped so local observers see a tunnel, not every destination hostname in cleartext (with important caveats about [DNS](/learn/what-is-dns.html)).
- **IP address seen by sites** — destinations generally see the VPN exit IP, not your ISP-assigned address. You can check this with a [public IP check](/check/ip.html).
- **Routing** — your device sends matching traffic into the tunnel interface (for example WireGuard) instead of straight out your default gateway.

## What a VPN does not do

- It does not stop websites from tracking you with cookies, accounts, or browser fingerprinting.
- It does not automatically anonymize you like Tor’s multi-hop design (see [VPN vs Tor](/learn/vpn-vs-tor.html)).
- It does not encrypt traffic **after** it leaves the VPN server toward the destination (HTTPS still matters).
- It cannot hide traffic from the VPN operator itself—see [Can a VPN see your history?](/learn/can-vpn-see-your-history.html).

## Types you will hear about

- **Consumer privacy VPNs** — apps that connect you to provider-operated servers (what VeritasVPN is).
- **Corporate / remote-access VPNs** — connect employees into a company network.
- **Site-to-site VPNs** — link two networks permanently.

Same broad idea; different threat models.

## How this relates to VeritasVPN

VeritasVPN is a WireGuard-based consumer VPN with an exit in Paraguay and always-on Veritas Shield while you are connected. We document limits openly—one live node today, source available under BSL—rather than promising “military-grade anonymity.”

If you want the next layer after understanding the concept, [download the clients](/downloads.html) or [run a privacy check](/check/) on your current connection.
