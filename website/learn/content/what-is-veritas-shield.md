---
title: What is Veritas Shield?
description: DNS security layered on VeritasVPN—malware, phishing, scam, crypto, and tracker blocking with optional ads, while you are connected.
category: protect
slug: what-is-veritas-shield
related: [what-is-protected-dns, what-is-dns, what-is-dns-leak, vpn-logging-explained]
updated: 2026-09-04
lede: Veritas Shield is the DNS security layer inside VeritasVPN. While the tunnel is up, lookups go through our gateway, threat feeds can NXDOMAIN dangerous names, and you can choose how aggressive the filter is.
---

## VPN + Veritas Shield

```
Internet
   ↑
Veritas Shield   ← DNS security (block + filter)
   ↑
VeritasVPN       ← encrypted tunnel + exit
   ↑
You
```

The product is **encrypted tunnel plus DNS security**—not a file antivirus and not a claim that every ad or tracker on earth disappears.

## What it does

1. **Forces DNS into the tunnel** so your ISP’s resolver is not the default path
2. **Encrypts upstream** (DNS-over-HTTPS) between our gateway and recursive resolvers
3. **Blocks known-bad and optional filter categories** with NXDOMAIN
4. **Reduces common bypasses** — plain DNS, DNS-over-TLS, and well-known public DoH targets from peers

## Presets

| Preset | Blocks |
|--------|--------|
| **Security** | Malware, phishing, scam, cryptomining |
| **Standard** (default) | Security + trackers |
| **Aggressive** | Standard + ads |

Ads stay off by default because ad lists cause more false positives (CDNs, banks, captive portals). Aggressive mode is opt-in; operators can also publish a small allowlist for known false positives.

## Honesty limits

- Upstream DoH resolvers still see hostnames we forward for **non-blocked** lookups
- Uncommon or custom DoH endpoints remain a **residual** bypass risk
- Shield does not inspect HTTPS bodies or replace browser updates

## Privacy

We do not log query names. In-app blocked counts are keyed by your temporary tunnel IP for the session—not your public WAN IP. Details: [Privacy Policy](/privacy.html).

## How to verify

Connect on Android or Linux, then run a [DNS leak test](/check/dns.html). Resolvers should show the provider path. Product docs also describe a harmless self-test name that must return NXDOMAIN when Shield is healthy.

## Related

For the general idea of filtering DNS inside a VPN (not product-specific), see [What is protected DNS?](/learn/what-is-protected-dns.html).
