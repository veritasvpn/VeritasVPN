# App DoH blocking (WireGuard peers)

## Goal

Stop common browser/app DNS-over-HTTPS bypasses of Protected DNS / Veritas Shield while connected.

## Design

Two layers, both scoped to VPN clients:

1. **nftables (`doh_v4` / `doh_v6`)** — drop TCP/UDP `443` from `wg0` → egress to curated public DoH resolver anycast IPs (Cloudflare, Google, Quad9, OpenDNS, AdGuard, …) on both IPv4 and IPv6. Agent upstream DoH uses host **OUTPUT**, so it stays up.
2. **DNS gateway** — built-in NXDOMAIN for known DoH service hostnames (`cloudflare-dns.com`, `dns.google`, …) so apps that resolve endpoints via `10.0.0.1` cannot bootstrap those resolvers.

Env:

- `DOH_BLOCK_IPS=none` — disable IP drops
- `DOH_BLOCK_EXTRA_IPS` — extra IPv4/IPv6 addresses to merge into the DoH sets

## Residual risk (accepted until Phase 3)

Unknown/custom DoH endpoints, CDN-hosted DoH not on the IP/hostname lists, and split-tunnel / per-app bypass traffic are not covered. Marketing and learn pages should stay honest about that. Full custom-DoH closure is Phase 3 (standalone DoH / browser DNS policy), not this hardening pass.
