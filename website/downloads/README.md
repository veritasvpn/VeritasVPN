# website/downloads/

## Useful information (humans)

Downloadable artifacts served by the static website.

| File | Purpose |
|------|---------|
| `veritasvpn-chrome.zip` | v0.3.5 build; Chrome extension with clearer auth errors |
| `veritasvpn-android.apk` | v0.1.6 signed Android release; streamed by `functions/downloads/veritasvpn-android.apk.js` |
| `veritasvpn-linux.deb` | v0.2.5 build; streamed by `functions/downloads/veritasvpn-linux.deb.js` from GitHub Releases (`v0.2.5`) |
| `veritasvpn-linux.AppImage` | v0.2.5 build; streamed by `functions/downloads/veritasvpn-linux.AppImage.js` from GitHub Releases (`v0.2.5`) |

## Useful information (AI)

- Regenerate after extension changes:
  `cd clients/browser-extension && zip -r ../../website/downloads/veritasvpn-chrome.zip . -x '*.DS_Store'`
- Source of truth: `clients/browser-extension/`
- Chrome Web Store publish replaces this ZIP flow eventually.
