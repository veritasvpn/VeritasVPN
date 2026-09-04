---
title: How does a VPN work?
description: Step-by-step look at tunnels, encryption, handshakes, and how traffic exits to the internet.
category: basics
slug: how-does-a-vpn-work
related: [what-is-a-vpn, what-is-wireguard, what-is-a-kill-switch]
updated: 2026-09-04
lede: Your device and a VPN server agree on keys, encapsulate packets inside an encrypted tunnel, then the server forwards them to the open internet.
---

## The short path

1. You authenticate (account, key, or device credential).
2. Your client and the server perform a **handshake** and derive session keys.
3. Your OS routes selected traffic into a virtual network interface.
4. Packets are **encrypted and encapsulated** toward the VPN server’s public endpoint.
5. The server decrypts, applies its policy (DNS, firewall, bandwidth), and **forwards** traffic to destinations.
6. Replies return through the same tunnel.

## Handshake and keys

Modern protocols such as [WireGuard](/learn/what-is-wireguard.html) use public-key cryptography so each side proves identity without shipping a long-lived password on every packet. After the handshake, symmetric keys encrypt bulk traffic efficiently.

If keys or peer records are wrong, the tunnel simply fails to pass traffic—prefer fail-closed behavior (see [kill switch](/learn/what-is-a-kill-switch.html)).

## Encapsulation

Think of your original IP packet as a letter. The VPN puts that letter inside a locked envelope addressed to the VPN server. Observers on your local network see envelopes to the VPN IP and port (for WireGuard, typically UDP), not every inner destination in cleartext.

## Exit and return path

At the server, the envelope is opened. The inner packet goes out via the provider’s uplink with **network address translation** in most consumer setups, so destinations see the VPN’s address. Return traffic is mapped back into your tunnel.

## DNS inside the tunnel

Name lookups should also go through the provider’s resolver while connected; otherwise you can suffer a [DNS leak](/learn/what-is-dns-leak.html). VeritasVPN pushes an in-tunnel DNS gateway and blocks common bypasses for peers.

## Failure modes worth knowing

- **Tunnel up, DNS elsewhere** — sites load but resolvers still see queries.
- **IPv6 or WebRTC bypass** — some apps punch outside the tunnel; test with a [VPN leak check](/check/vpn.html).
- **Split tunnel** — some traffic intentionally skips the VPN; see [split tunneling](/learn/what-is-split-tunneling.html).

Understanding the path makes marketing claims easier to evaluate—and makes honest products easier to trust.
