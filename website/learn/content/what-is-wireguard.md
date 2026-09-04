---
title: What is WireGuard?
description: Why WireGuard became the default modern VPN protocol—and what “simple” really buys you.
category: basics
slug: what-is-wireguard
related: [how-does-a-vpn-work, what-is-a-vpn, what-is-encryption]
updated: 2026-09-04
lede: WireGuard is a lean VPN protocol built into modern operating systems kernels and userspace stacks, focused on strong cryptography and a small codebase.
---

## Design goals

WireGuard aims to be **auditable and fast**: a small set of modern crypto primitives, static key pairs per peer, and UDP transport. Less negotiable complexity than older IPsec or OpenVPN stacks means fewer sharp edges—and a clearer threat model.

## How a peer looks

Each side has a private key and a public key. Peers are configured with each other’s public keys and allowed IPs. There is no sprawling cipher suite negotiation in the classic sense; the protocol suite is fixed and versioned carefully.

## Why products switched

- Lower overhead on mobile and constrained links
- Faster handshakes and roaming behavior
- Easier secure defaults

That does not make every WireGuard *product* trustworthy—operations, logging, DNS, and jurisdiction still matter.

## VeritasVPN and WireGuard

VeritasVPN provisions WireGuard peers for Android and Linux clients. The tunnel carries your traffic to the Paraguay exit; Veritas Shield rides alongside while connected. Protocol choice is necessary but not sufficient: read [VPN logging explained](/learn/vpn-logging-explained.html) for the rest of the trust story.
