# Features shipped (source of truth)

Last updated: 2026-10-01

## Core VPN
- WireGuard on Linux desktop, Android, CLI; Chrome HTTP proxy extension
- Advertised UDP endpoint often public **443** (router → host **51820**)
- Optional **Stealth** (Android and Linux desktop): WireGuard over TLS/WebSocket (`wstunnel`) on TCP **443**. Android defaults to Auto (plain UDP, then Stealth if that handshake does not complete). UDP only and Stealth always are in Settings → Connection → Stealth, separate from split tunnel. Linux is a Settings toggle. Not a claim of undetectability.
- Always-on private DNS gateway (while connected) with DoH upstreams + **Veritas Shield** categorized blocklists. Premium toggles (Settings → Veritas Shield on Android and Linux): Block malicious sites (default on: malware, phishing, scam, crypto), Block ads (default off), Block adult sites (default off). Trackers stay with the connected Standard policy and have no toggle yet. Legacy presets remain a compatibility alias. DNS NXDOMAIN only, not HTML ad stripping. Well-known public DoH resolver IPs/hostnames blocked for peers; Prometheus has no query names (category labels only); UI blocked counts are per tunnel IP / session delta; ops allowlist via `DNS_SHIELD_ALLOWLIST`
- Per-device bandwidth cap (~150 Mbps)
- 5 devices; Premium gate via BTCPay (Bitcoin)

## Client safety
- Linux: firewall + route kill switch mandatory while connected (no in-app off toggle)
- Android: The first Connect calls `VpnService.prepare` so VeritasVPN is registered in the system VPN list (and the allow dialog is shown when needed) before the Always-on gate. The tunnel stays blocked until system Always-on VPN and Block connections without VPN are enabled for VeritasVPN. Release builds on Android 12+ / HyperOS cannot read the hidden always-on package setting; detection then treats readable lockdown plus this app being the prepared VPN as both switches on, and still rejects another VPN such as Tailscale. The VPN list master switch is not required. The app explains why, deep-links to system VPN settings, and re-checks on resume. There is no in-app off toggle and no skip; apps cannot force the OS switches.
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
