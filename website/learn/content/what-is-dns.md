---
title: What is DNS?
description: How the Domain Name System turns names into addresses—and who learns what you look up.
category: basics
slug: what-is-dns
related: [what-is-dns-leak, what-is-protected-dns, what-is-an-ip-address]
updated: 2026-09-04
lede: DNS is the internet’s phone book: it translates human-readable names into IP addresses your device can dial.
---

## Why names need translation

Browsers and apps connect to **IP addresses**. Humans remember names like `veritasvpn.cloud`. The Domain Name System (DNS) bridges the two.

When you visit a site, your device asks a **resolver**: “What address is this name?” The resolver returns A/AAAA records (and more). Only then does the TCP/TLS session begin.

## Who runs resolvers?

- Your **router / ISP** often hands out a default resolver over DHCP.
- **Public resolvers** (Cloudflare, Google, Quad9, and others) are reachable on the open internet.
- **App-level DNS-over-HTTPS** may bypass the system resolver entirely.

Whoever answers the query typically learns **which hostnames you resolve**, even when the later HTTPS session is encrypted. That is why DNS privacy matters as much as “HTTPS everywhere.”

## Recursive resolution (simplified)

1. Stub resolver on your device asks a recursive resolver.
2. The recursive resolver walks the hierarchy (root → TLD → authoritative) if needed, or serves from cache.
3. Answer returns to your device and is cached for a TTL.

You rarely talk to root servers yourself; the recursive resolver does that work.

## Encrypted DNS

- **DNS-over-TLS (DoT)** — DNS on port 853 with TLS.
- **DNS-over-HTTPS (DoH)** — DNS inside HTTPS, often port 443.

Encryption hides queries from the local network path, but the **chosen resolver** still sees them. Moving queries to a VPN’s resolver changes who that party is.

## Practical takeaway

Protecting browsing without protecting DNS leaves a clear text map of your destinations for whoever operates your resolver. Use a trustworthy path—such as [protected DNS inside a VPN](/learn/what-is-protected-dns.html)—and verify with a [DNS leak test](/check/dns.html).
