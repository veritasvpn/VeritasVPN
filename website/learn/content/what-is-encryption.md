---
title: What is encryption?
description: Confidentiality, integrity, and authenticity—without the buzzwords that obscure the tradeoffs.
category: basics
slug: what-is-encryption
related: [how-does-a-vpn-work, what-is-wireguard, vpn-logging-explained]
updated: 2026-09-04
lede: Encryption transforms data so only parties with the right keys can read or verify it—protecting traffic in motion and files at rest.
---

## Three properties people mix up

- **Confidentiality** — outsiders cannot read the content.
- **Integrity** — tampering is detectable.
- **Authenticity** — you know whom you are talking to (within the trust model).

TLS in your browser and modern VPN tunnels aim at all three for the segments they cover.

## Symmetric vs public-key

- **Symmetric** algorithms use the same key to encrypt and decrypt (fast for bulk data).
- **Public-key** cryptography uses key pairs to establish trust and share secrets without a pre-shared password on every packet.

VPNs typically use public-key handshakes, then symmetric encryption for the stream.

## What encryption does not magically fix

Encrypted pipes can still leak **timing, size, and destination metadata** to some observers. Endpoints (apps, accounts, malware) can betray you after decryption. A VPN encrypts the path to its server; it does not encrypt the entire internet beyond that server.

## Algorithms vs marketing

“Military-grade” is not a specification. Prefer named, reviewed protocols (WireGuard, TLS 1.3) and clear key management over adjectives. VeritasVPN uses WireGuard for the tunnel—see [What is WireGuard?](/learn/what-is-wireguard.html).
