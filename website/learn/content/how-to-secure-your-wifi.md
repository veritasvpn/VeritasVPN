---
title: How to secure your Wi‑Fi
description: Practical steps to harden a home wireless network without turning your router into a science project.
category: protect
slug: how-to-secure-your-wifi
related: [is-public-wifi-safe, what-is-a-vpn, what-is-dns]
updated: 2026-09-04
lede: Strong Wi‑Fi hygiene reduces local attackers and drive-by abuse—complementary to using a VPN on untrusted networks.
---

## Baseline checklist

1. **Change default admin passwords** on the router.
2. Use **WPA3** if all devices support it; otherwise WPA2-AES with a long passphrase.
3. Disable **WPS** (PIN pairing is a frequent weak point).
4. Keep router firmware updated.
5. Prefer a **guest network** for visitors and IoT when available.

## DNS and filtering on the LAN

Pointing the whole home at a filtering resolver can block malware domains for devices that never run a VPN. That is different from [Veritas Shield inside VeritasVPN](/learn/what-is-protected-dns.html), which applies while the tunnel is up.

## Remote management

Turn off WAN-side admin access unless you truly need it. Cloud-managed routers add convenience and another vendor trust relationship—decide deliberately.

## When a VPN still matters

Home Wi‑Fi hardening does not hide your traffic from the ISP. For that threat, use a VPN. For café Wi‑Fi, assume the LAN is hostile regardless of your home setup—see [Is public Wi‑Fi safe?](/learn/is-public-wifi-safe.html).
