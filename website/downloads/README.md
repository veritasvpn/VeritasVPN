# website/downloads/

## Useful information (humans)

**Production traffic** is served by Cloudflare Functions that stream from GitHub Releases (Linux and Android `v0.2.77`). The local fallback files in this directory are not deployed to Pages and retain their own checksum manifest until they are refreshed separately.

| File | Purpose |
|------|---------|
| `veritasvpn-android.apk` | Local signed-APK fallback — must match the local SHA-256 manifest; public downloads stream GitHub `v0.2.77` |
| `veritasvpn-linux.deb` | Local Linux .deb fallback — gitignored; public downloads stream GitHub `v0.2.77` |
| `veritasvpn-linux.AppImage` | Local Linux AppImage fallback — gitignored; public downloads stream GitHub `v0.2.77` |
| `veritasvpn-chrome.zip` | Sideload zip from `clients/browser-extension` (source `0.3.7`); public download remains paused |

`SHA256SUMS` in this directory lists hashes for the files above.

## Useful information (AI)

Refresh from the published tags:

```bash
cd website/downloads
curl -fL -o veritasvpn-android.apk "https://github.com/veritasvpn/VeritasVPN/releases/download/v0.2.77/veritasvpn-android.apk"
curl -fL -o veritasvpn-linux.deb "https://github.com/veritasvpn/VeritasVPN/releases/download/v0.2.77/veritasvpn-linux.deb"
curl -fL -o veritasvpn-linux.AppImage "https://github.com/veritasvpn/VeritasVPN/releases/download/v0.2.77/veritasvpn-linux.AppImage"
sha256sum -c SHA256SUMS --ignore-missing
```

Rebuild Chrome zip from source:

```bash
cd clients/browser-extension && zip -r ../../website/downloads/veritasvpn-chrome.zip . -x '*.DS_Store'
```

Then rewrite `SHA256SUMS` with `sha256sum` of the four artifacts.
