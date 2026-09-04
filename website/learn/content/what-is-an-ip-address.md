---
title: What is an IP address?
description: Public vs private IPs, what they reveal, and why VPN exits change the address sites see.
category: basics
slug: what-is-an-ip-address
related: [what-is-a-vpn, why-server-location-matters, what-is-dns]
updated: 2026-09-04
lede: An IP address is a number that identifies a network interface so packets can be delivered across the internet.
---

## Public vs private

- **Public IPs** are globally routable. Your ISP assigns one (or a shared carrier-grade NAT address) to your connection. Websites generally see this address—or your VPN’s—when you connect.
- **Private IPs** (like `192.168.1.10` or `10.0.0.2`) stay inside a LAN or tunnel. They are not unique on the whole internet.

A home router performs **NAT** so many devices share one public IPv4 address.

## What an IP can suggest

A public IP can roughly indicate **ISP and geography**, power blocklists, and rate limits. It is not a precise street address by itself, but it is a stable-enough identifier for tracking across sessions if it rarely changes.

## IPv4 and IPv6

IPv6 addresses are longer and often assigned per device path. If your VPN handles only IPv4 while IPv6 stays native, traffic—or leaks—may bypass the tunnel. Always test both families when you care about exposure ([VPN leak test](/check/vpn.html)).

## VPN exit IPs

When a full-tunnel VPN works, destinations see the **provider exit IP**. That is useful for hiding your ISP address from sites, and it also means those sites may geolocate you to the VPN’s region—see [why server location matters](/learn/why-server-location-matters.html).

Check what the world sees right now with an [IP check](/check/ip.html).
