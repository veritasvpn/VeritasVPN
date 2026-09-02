# website/downloads/

## Useful information (humans)

Local copies of downloadable artifacts. **Production traffic** is served by Cloudflare Functions that stream from GitHub Releases (Android + Linux `v0.2.33`). These files keep the repo / Dell hostPath fallback aligned with that release.

| File | Purpose |
|------|---------|
| `veritasvpn-android.apk` | Signed Android release (`0.2.28`) — must match GitHub `v0.2.33` SHA-256 |
| `veritasvpn-linux.deb` | Linux .deb (`0.2.33`) — gitignored; keep in sync on disk for hostPath |
| `veritasvpn-linux.AppImage` | Linux AppImage (`0.2.33`) — gitignored; keep in sync on disk for hostPath |
| `veritasvpn-chrome.zip` | Sideload zip from `clients/browser-extension` (source `0.3.7`); public download remains paused |

`SHA256SUMS` in this directory lists hashes for the files above.

## Useful information (AI)

Refresh from the published tag:

```bash
TAG=v0.2.33
cd website/downloads
curl -fL -o veritasvpn-android.apk "https://github.com/veritasvpn/VeritasVPN/releases/download/${TAG}/veritasvpn-android.apk"
curl -fL -o veritasvpn-linux.deb "https://github.com/veritasvpn/VeritasVPN/releases/download/${TAG}/veritasvpn-linux.deb"
curl -fL -o veritasvpn-linux.AppImage "https://github.com/veritasvpn/VeritasVPN/releases/download/${TAG}/veritasvpn-linux.AppImage"
sha256sum -c SHA256SUMS --ignore-missing
```

Rebuild Chrome zip from source:

```bash
cd clients/browser-extension && zip -r ../../website/downloads/veritasvpn-chrome.zip . -x '*.DS_Store'
```

Then rewrite `SHA256SUMS` with `sha256sum` of the four artifacts.
