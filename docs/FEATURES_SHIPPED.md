# Features shipped (source of truth)

Last updated: 2026-09-30

## Core VPN
- WireGuard on Linux desktop, Android, CLI; Chrome HTTP proxy extension
- Advertised UDP endpoint often public **443** (router → host **51820**)
- Optional **Stealth** (Android and Linux desktop): WireGuard over TLS/WebSocket (`wstunnel`) on TCP **443**. Android defaults to Auto (plain UDP, then Stealth if that handshake does not complete; UDP only and Stealth always are in connection settings). Linux is a Settings toggle. Not a claim of undetectability.
- Always-on private DNS gateway (while connected) with DoH upstreams + **Veritas Shield** categorized blocklists and per-peer presets (Security / Standard / Aggressive; ads off unless Aggressive); well-known public DoH resolver IPs/hostnames blocked for peers; Prometheus has no query names (category labels only); UI blocked counts are per tunnel IP / session delta; ops allowlist via `DNS_SHIELD_ALLOWLIST`
- Per-device bandwidth cap (~150 Mbps)
- 5 devices; Premium gate via BTCPay (Bitcoin)

## Client safety
- Linux: firewall + route kill switch mandatory while connected (no in-app off toggle)
- Android: Connect is blocked until system Always-on VPN and Block connections without VPN are enabled for VeritasVPN. The app explains why, deep-links to system VPN settings, and re-checks on resume. There is no in-app off toggle and no skip; apps cannot force the OS switches.
- Auto-reconnect always on (Linux desktop + Android); no user toggle
- Split tunnel: exclude LAN (desktop/Android); Android per-app bypass

## Account / site
- Anonymous Account ID + email accounts
- Account dashboard: subscription, devices, downloads, security
- FAQ documents kill switch, split tunnel, and stealth
- Free website privacy checks at `/check/` (IP, DNS leak, VPN leak, browser reveal, breach, report)

## Not shipped
- Multi-hop / multi-region (needs more nodes)
- Dedicated IP add-on (needs extra public IPs)
- AmneziaWG / claim of undetectability
