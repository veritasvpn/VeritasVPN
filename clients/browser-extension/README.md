# VeritasVPN Chrome extension

Manifest V3 extension that signs into the production Veritas API and protects Chrome traffic through the authenticated Veritas browser gateway in Paraguay.

## Install

1. Download and unzip `veritasvpn-chrome.zip` from veritasvpn.cloud.
2. Open `chrome://extensions`, enable **Developer mode**, and choose **Load unpacked**.
3. Select the unzipped folder, sign in, and click **Connect now**.

Connect refreshes the user session, enables Chrome's HTTP CONNECT proxy, supplies the access token only to proxy authentication challenges, and verifies that internet egress matches the Paraguay server before displaying Connected. A failed check clears Chrome proxy settings automatically.

## Files

- `manifest.json`: production API, proxy, and proxy-auth permissions
- `popup.html`, `css/popup.css`, `js/popup.js`: Android-aligned interface and state machine
- `js/auth.js`: auth/session, fail-closed connection, and egress validation
- `js/background.js`: proxy authentication and health cleanup
- `js/config.js`: production gateway configuration
- Live website artifact: `website/downloads/veritasvpn-chrome.zip`
