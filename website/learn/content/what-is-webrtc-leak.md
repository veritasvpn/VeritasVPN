---
title: What is a WebRTC leak?
description: How browsers can reveal local or public IPs through WebRTC even when a VPN is connected.
category: threats
slug: what-is-webrtc-leak
related: [what-is-dns-leak, what-is-an-ip-address, what-is-a-kill-switch]
updated: 2026-09-04
lede: WebRTC helps real-time audio and video in browsers—and its connection setup can expose addresses outside your VPN path.
---

## What WebRTC is for

WebRTC lets browsers talk peer-to-peer for calls, meetings, and some interactive apps. To punch through NATs, it gathers **ICE candidates**: possible addresses the browser might use, including local LAN addresses and sometimes reflexive public addresses.

## How that becomes a leak

JavaScript on a page can often read those candidates. If your VPN does not cover every path the browser probes, a site may learn an address you intended to hide—classic “VPN connected, IP still visible” reports.

This is separate from a [DNS leak](/learn/what-is-dns-leak.html), though both undermine the mental model of “the VPN hides me.”

## What to do

- Test with a [VPN leak check](/check/vpn.html) while connected
- Prefer full-tunnel modes and OS-level kill switches where available
- Understand that disabling WebRTC entirely can break legitimate apps—tradeoffs are real

Browser extensions that “force WebRTC through proxy” vary in quality; treat them as helpers, not guarantees.
