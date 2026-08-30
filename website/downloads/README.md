# website/downloads/

## Useful information (humans)

Downloadable artifacts served by the static website.

| File | Purpose |
|------|---------|
| `veritasvpn-chrome.zip` | Internal v0.3.6 validation build; public download remains paused pending gateway approval and end-to-end testing |
| `veritasvpn-android.apk` | v0.2.14 signed Android release; streamed by `functions/downloads/veritasvpn-android.apk.js` from GitHub Releases (`v0.2.14`) |
| `veritasvpn-linux.deb` | v0.2.14 build; streamed by `functions/downloads/veritasvpn-linux.deb.js` from GitHub Releases (`v0.2.14`) |
| `veritasvpn-linux.AppImage` | v0.2.14 build; streamed by `functions/downloads/veritasvpn-linux.AppImage.js` from GitHub Releases (`v0.2.14`) |

## Useful information (AI)

- Regenerate after extension changes:
  `cd clients/browser-extension && zip -r ../../website/downloads/veritasvpn-chrome.zip . -x '*.DS_Store'`
- Source of truth: `clients/browser-extension/`
- Chrome Web Store publish replaces this ZIP flow eventually.
