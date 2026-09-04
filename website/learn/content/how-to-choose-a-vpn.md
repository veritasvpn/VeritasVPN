---
title: How to choose a VPN
description: A skepticism checklist for logging claims, jurisdiction, protocols, and independent verification.
category: protect
slug: how-to-choose-a-vpn
related: [vpn-logging-explained, vpn-vs-tor, why-server-location-matters]
updated: 2026-09-04
lede: Pick a VPN by threat model and verifiable claims—not by affiliate ranking tables.
---

## Start with the adversary

- Café Wi‑Fi snoops → encrypted tunnel + kill switch
- Hide ISP address from sites → working full tunnel + DNS
- Strong anonymity vs nation-state → a consumer VPN is probably the wrong tool ([VPN vs Tor](/learn/vpn-vs-tor.html))

## Questions that matter

1. **What is logged, for how long?** Demand specifics.
2. **Where are companies and servers?** Jurisdiction is not marketing flavor text.
3. **Which protocol?** Prefer modern defaults like WireGuard.
4. **Can you pay privately?** Matters for some users.
5. **Is the client inspectable?** Source-available helps.
6. **Do they admit limits?** One region, pending audits, known DoH residuals—honesty is a feature.

## Red flags

- Guaranteed anonymity
- “Unlimited” everything with no engineering discussion
- Audits that never name the build you run
- Reviews that are only coupon pages

## Trying VeritasVPN

We run WireGuard from Paraguay, document protected DNS behavior, take Bitcoin for Premium, and keep source available under BSL. We will not claim a global anycast fantasy we do not operate. [Download](/downloads.html) or read [logging explained](/learn/vpn-logging-explained.html) first.
