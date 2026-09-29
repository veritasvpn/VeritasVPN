# Chrome Web Store assets

These images are listing art for the Chrome Web Store. They are not part of the extension package. `website/downloads/veritasvpn-chrome.zip` and the release workflow omit this directory.

Screenshots show the real popup (`popup.html`, `css/popup.css`, and `js/popup.js`) with a local session fixture. Captions describe a Chrome HTTP CONNECT proxy. The extension is not a system-wide WireGuard VPN and does not show ads.

| File | Pixels | Listing use |
|------|--------|-------------|
| `screenshot-signin-1280x800.png` | 1280×800 | Signed-out popup: email sign-in and Account ID |
| `screenshot-disconnected-1280x800.png` | 1280×800 | Signed in, Chrome proxy off |
| `screenshot-connected-1280x800.png` | 1280×800 | Connected after the Paraguay egress check |
| `screenshot-blocked-1280x800.png` | 1280×800 | Fail-closed state when the proxy drops |
| `screenshot-network-map-1280x800.png` | 1280×800 | Network map (optional coarse location) |
| `promo-small-440x280.png` | 440×280 | Small promo tile |

Upload the 1280×800 PNGs as screenshots (at least two; all five are suitable). Upload `promo-small-440x280.png` as the small promo tile. Do not put this folder inside the extension zip.
