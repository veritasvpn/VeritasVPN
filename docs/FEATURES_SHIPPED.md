# Features shipped (source of truth)

Last updated: 2026-09-02

## Core VPN
- WireGuard on Linux desktop, Android, CLI; Chrome HTTP proxy extension
- Advertised UDP endpoint often public **443** (router → host **51820**)
- Optional **Stealth** (Linux desktop): WireGuard over TLS/WebSocket (`wstunnel`) on TCP **443**
- Premium **port forwarding** (max 2): public IP:port → peer; recommended **40000–49999**
- Always-on private DNS gateway (while connected) with DoH upstreams + **Veritas Shield** categorized blocklists and per-peer presets (Security / Standard / Aggressive; ads off unless Aggressive); well-known public DoH resolver IPs/hostnames blocked for peers; Prometheus has no query names (category labels only); UI blocked counts are per tunnel IP / session delta; ops allowlist via `DNS_SHIELD_ALLOWLIST`
- Per-device bandwidth cap (~150 Mbps)
- 5 devices; Premium gate via BTCPay (Bitcoin)

## Client safety
- Linux: firewall + route kill switch mandatory while connected (no in-app off toggle)
- Android: while connected, full-tunnel VpnService protects traffic; for leak protection if the tunnel drops, enable Always-on VPN + Block connections without VPN in Android system settings (apps cannot force these on)
- Desktop auto-reconnect; Android auto-reconnect after established session
- Split tunnel: exclude LAN (desktop/Android); Android per-app bypass

## Account / site
- Anonymous Account ID + email accounts
- Account dashboard: subscription, devices, port forwards, downloads, security
- FAQ documents kill switch, split tunnel, port forwarding, stealth
- Free website privacy checks at `/check/` (IP, DNS leak, VPN leak, browser reveal, breach, report)

## Not shipped
- Multi-hop / multi-region (needs more nodes)
- Dedicated IP add-on (needs extra public IPs)
- Android Stealth transport (API fields only; use Linux desktop)
- AmneziaWG / claim of undetectability
