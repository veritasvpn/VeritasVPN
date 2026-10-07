# website/downloads/

## Useful information (humans)

**Production traffic** is served by Cloudflare Functions that stream from GitHub Releases only when the bytes match the SHA-256 pinned in `functions/_lib/releases.js`. Android is served from `android-v0.2.90` (app version 0.2.90). Linux is served from desktop `v0.2.90` (git tag `linux-v0.2.90`). The local fallback files in this directory are not deployed to Pages and retain their own checksum manifest until they are refreshed separately.

| File | Purpose |
|------|---------|
| `veritasvpn-android.apk` | Local signed-APK fallback — must match the local SHA-256 manifest; public downloads stream GitHub `android-v0.2.90` |
| `veritasvpn-linux.deb` | Local Linux .deb fallback — gitignored; public downloads stream GitHub `linux-v0.2.90` |
| `veritasvpn-linux.AppImage` | Local Linux AppImage fallback — gitignored; public downloads stream GitHub `linux-v0.2.90` |
| `veritasvpn-chrome.zip` | Sideload zip from `clients/browser-extension` (source `0.3.7`); public download remains paused |

`SHA256SUMS` in this directory lists hashes for the files above.

## Useful information (AI)

Refresh from the published tags:

```bash
cd website/downloads
curl -fL -o veritasvpn-android.apk "https://github.com/veritasvpn/VeritasVPN/releases/download/android-v0.2.90/veritasvpn-android.apk"
curl -fL -o veritasvpn-linux.deb "https://github.com/veritasvpn/VeritasVPN/releases/download/linux-v0.2.90/veritasvpn-linux.deb"
curl -fL -o veritasvpn-linux.AppImage "https://github.com/veritasvpn/VeritasVPN/releases/download/linux-v0.2.90/veritasvpn-linux.AppImage"
sha256sum -c SHA256SUMS --ignore-missing
```

Rebuild Chrome zip from source:

Run from the repository root. `store-assets/` is Chrome Web Store listing art only and must stay out of the zip.

```bash
ROOT="$(pwd)"
rm -rf /tmp/veritasvpn-chrome-pack
mkdir -p /tmp/veritasvpn-chrome-pack
cp -a clients/browser-extension/. /tmp/veritasvpn-chrome-pack/
rm -rf /tmp/veritasvpn-chrome-pack/store-assets
( cd /tmp/veritasvpn-chrome-pack && zip -r "$ROOT/website/downloads/veritasvpn-chrome.zip" . -x '*.DS_Store' )
```

Then rewrite `SHA256SUMS` with `sha256sum` of the four artifacts.
