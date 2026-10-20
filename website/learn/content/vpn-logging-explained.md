---
title: VPN logging explained
description: Connection logs, metadata, and destination history—how to read privacy policies without wishful parsing.
category: protect
slug: vpn-logging-explained
related: [can-vpn-see-your-history, how-to-choose-a-vpn, what-is-protected-dns]
updated: 2026-10-19
lede: “No logs” is meaningless until you know which events are stored, aggregated, or never written.
---

## Categories of data

- **Account & billing** — almost every paid VPN keeps some
- **Operational metrics** — bandwidth totals, error counts, server health
- **Connection metadata** — timestamps, VPN IPs, device counts
- **Destination history** — domains, IPs, URLs you visited (the sensitive stuff)

Privacy-respecting designs avoid destination history and minimize connection metadata.

## DNS is a logging surface

Even without “browsing history” tables, **resolver logs** can reconstruct a hostname timeline. Ask whether DNS is logged and who operates upstream resolvers.

## Memory vs disk

Some providers process packets without writing destinations to durable storage. That is better—but still requires you to trust their code and operators. Memory can be forensically interesting after compromise; policies are not magic.

## VeritasVPN stance

We aim for operational counts without query names in Prometheus, tunnel-IP session counters that clear with peers, and account category counts with no domain names kept for about eight days. A blocked name used for a notice is held in gateway memory for up to about 10 minutes and in a Redis cache for about two minutes, keyed by tunnel IP, then deleted. It is not written to disk. Upstream DoH resolvers still see forwarded hostnames for non-blocked lookups. Read the [privacy policy](/privacy.html) rather than a slogan.
