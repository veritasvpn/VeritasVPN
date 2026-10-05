---
title: What is Veritas Shield?
description: DNS security layered on VeritasVPN. Premium toggles for malicious sites, trackers, ads, and adult sites. Blocking is DNS NXDOMAIN only.
category: protect
slug: what-is-veritas-shield
related: [what-is-protected-dns, what-is-dns, what-is-dns-leak, vpn-logging-explained]
updated: 2026-10-05
lede: Veritas Shield is the DNS security layer inside VeritasVPN. While the tunnel is up, lookups go through our gateway and threat feeds can NXDOMAIN dangerous names. Premium can turn malicious sites, trackers, ads, and adult sites on or off independently.
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

## Toggles

On Android and Linux, Settings → Veritas Shield has four Premium controls. They change which domain lists the gateway enforces for your tunnel. They do not edit the page.

- **Block malicious sites** (default on) — malware, phishing, scam, and cryptomining domains
- **Block trackers** (default on) — known tracker domains only. This list is separate from ads
- **Block ads** (default off) — known ad domains
- **Block adult sites** (default off) — known adult domains

Ads stay off by default because ad lists cause more false positives (CDNs, banks, captive portals). Adult filtering is off until you turn it on. Operators can still publish a small allowlist for known false positives.

Free accounts see Upgrade on these controls and the API rejects a change. A connected peer that never saved toggles keeps today's Standard coverage: malicious sites and trackers on, ads and adult sites off. Peers that already saved the older three controls keep tracker blocking on, so the new toggle does not weaken that policy.

Changing a toggle updates DNS for the current connection. You do not need to rebuild the tunnel.

## Honesty limits

- This is **DNS domain blocking**, not full-page ad removal and not in-page tracker removal. An ad or tracker served from the same host as the page can still appear.
- Upstream DoH resolvers still see hostnames we forward for **non-blocked** lookups
- Uncommon or custom DoH endpoints remain a **residual** bypass risk
- Shield does not inspect HTTPS bodies or replace browser updates

## Privacy

We do not log query names. In-app blocked counts are keyed by your temporary tunnel IP for the session—not your public WAN IP. The toggles are stored with peer metadata so the gateway can apply them. Details: [Privacy Policy](/privacy.html).

## How to verify

Connect on Android or Linux with Premium, then run a [DNS leak test](/check/dns.html). Resolvers should show the provider path.

With **Block malicious sites** on (the default), this harmless name must return NXDOMAIN:

`dns-protection-test.veritasvpn.invalid`

With **Block adult sites** on, this reserved name must return NXDOMAIN, and it should resolve when that toggle is off:

`adult-protection-test.veritasvpn.invalid`

## Related

For the general idea of filtering DNS inside a VPN (not product-specific), see [What is protected DNS?](/learn/what-is-protected-dns.html).
