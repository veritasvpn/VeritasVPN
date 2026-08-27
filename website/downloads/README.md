# website/downloads/

## Useful information (humans)

Downloadable artifacts served by the static website.

| File | Purpose |
|------|---------|
| `veritasvpn-chrome.zip` | Unpacked Chrome extension package for “Load unpacked” installs |
| `veritasvpn-android.apk` | Streamed by `functions/downloads/veritasvpn-android.apk.js` |
| `veritasvpn-linux.deb` | v0.2.4 build; streamed by `functions/downloads/veritasvpn-linux.deb.js` from GitHub Releases (`v0.2.4`) |
| `veritasvpn-linux.AppImage` | v0.2.4 build; streamed by `functions/downloads/veritasvpn-linux.AppImage.js` from GitHub Releases (`v0.2.4`) |
| `veritasvpn-android.apk` | Signed Android release APK |

## Useful information (AI)

- Regenerate after extension changes:
  `cd clients/browser-extension && zip -r ../../website/downloads/veritasvpn-chrome.zip . -x '*.DS_Store'`
- Source of truth: `clients/browser-extension/`
- Chrome Web Store publish replaces this ZIP flow eventually.
