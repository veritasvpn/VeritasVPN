# website/downloads/

## Useful information (humans)

Local copies of downloadable artifacts. **Production traffic** is served by Cloudflare Functions that stream from GitHub Releases (Linux `v0.2.37`; Android APK still from `v0.2.34`). These files keep the repo / Dell hostPath fallback aligned with that release.

| File | Purpose |
|------|---------|
| `veritasvpn-android.apk` | Signed Android release (`0.2.29`) — must match GitHub `v0.2.34` SHA-256 |
| `veritasvpn-linux.deb` | Linux .deb (`0.2.37`) — gitignored; keep in sync on disk for hostPath |
| `veritasvpn-linux.AppImage` | Linux AppImage (`0.2.37`) — gitignored; keep in sync on disk for hostPath |
| `veritasvpn-chrome.zip` | Sideload zip from `clients/browser-extension` (source `0.3.7`); public download remains paused |

`SHA256SUMS` in this directory lists hashes for the files above.

## Useful information (AI)

Refresh from the published tags:

```bash
cd website/downloads
curl -fL -o veritasvpn-android.apk "https://github.com/veritasvpn/VeritasVPN/releases/download/v0.2.34/veritasvpn-android.apk"
curl -fL -o veritasvpn-linux.deb "https://github.com/veritasvpn/VeritasVPN/releases/download/v0.2.37/veritasvpn-linux.deb"
curl -fL -o veritasvpn-linux.AppImage "https://github.com/veritasvpn/VeritasVPN/releases/download/v0.2.37/veritasvpn-linux.AppImage"
sha256sum -c SHA256SUMS --ignore-missing
```

Rebuild Chrome zip from source:

```bash
cd clients/browser-extension && zip -r ../../website/downloads/veritasvpn-chrome.zip . -x '*.DS_Store'
```

Then rewrite `SHA256SUMS` with `sha256sum` of the four artifacts.
