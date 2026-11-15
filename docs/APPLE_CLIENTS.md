# VeritasVPN for iPhone, iPad and Mac

Status: **code complete for Direct (UDP) connect, unsigned, not yet run on a
device.** There is no Apple Developer account yet, so nothing has been signed,
installed or published. CI builds both apps unsigned and runs the unit tests
(`.github/workflows/apple.yml`).

The Android app is the template. Screens, flows, strings, colours, the logo
hero animation and the API contract follow `android/`.

## Layout

```
clients/apple/
  project.yml                 XcodeGen spec (the .xcodeproj is generated, git-ignored)
  Config/                     Base + Signing xcconfigs (Team ID / bundle id placeholders)
  Packages/VeritasCore/       SwiftPM package: all platform-independent logic + unit tests
  VeritasVPN/                 SwiftUI app, shared by the iOS and macOS targets
    App/ Model/ Views/ Theme/ Platform/ Resources/
  iOS/, macOS/                Info.plist + entitlements per app
  PacketTunnel/Sources/       NEPacketTunnelProvider (shared by both extensions)
  PacketTunnel/iOS|macOS/     Info.plist + entitlements per extension
  Scripts/                    wireguard-go bridge build, l10n generator, es-419 strings
```

### Targets

| Target | Product | Bundle id (default) |
| --- | --- | --- |
| `VeritasVPN-iOS` | iOS / iPadOS app | `cloud.veritasvpn.vpn` |
| `PacketTunnel-iOS` | packet tunnel app extension | `cloud.veritasvpn.vpn.tunnel` |
| `VeritasVPN-macOS` | macOS app (sandboxed) | `cloud.veritasvpn.vpn` |
| `PacketTunnel-macOS` | packet tunnel app extension | `cloud.veritasvpn.vpn.tunnel` |
| `WireGuardGoBridge-iOS/-macOS` | `libwg-go.a` (External Build System) | – |

The same bundle id on both platforms allows a single "universal purchase" App
Store record later. The prefix is `VERITAS_BUNDLE_ID_PREFIX` in
`Config/Signing.xcconfig`.

### macOS: app extension, not a system extension

The Mac uses a **packet-tunnel app extension** inside a sandboxed app. That is
the only form allowed in the Mac App Store and TestFlight. A Network Extension
*system extension* is only needed for Developer ID (outside the store)
distribution; it can be added later as a separate target if we ever ship a
notarized DMG. The tunnel code would be the same.

### VeritasCore (Swift package)

Foundation/CryptoKit/Security only, so `swift test` runs it on any Mac:

- `APIClient` – `https://api.veritasvpn.cloud`, `X-Veritas-Client: ios|macos`
  (telemetry only), ephemeral `URLSession` (no cookies, no cache), bounded
  retries for connect failures/timeouts, Cloudflare/edge pages and bad JSON
  mapped to "server unavailable".
- `SessionManager` – single-flight refresh: concurrent callers share one
  `/auth/refresh`. Only a **401 from `/auth/refresh` on a foreground call**
  ends the session. Background calls (billing poll, Shield counts, email
  refresh, peer delete) throw `APIError.backgroundAuth`, which shows
  "Sign in again" and never clears the session.
- `ErrorText` + `Messages` – port of the desktop `errorMapper.ts` /
  `connectError.ts`. Every error becomes a written sentence. Raw codes, HTTP
  bodies, NSError text and lower-case server log text never reach the UI.
  Example: Cloudflare 1033 → "Can't reach VeritasVPN servers right now. Please
  check your internet connection or try again in a few minutes."
- `AuthService` – email sign in/up, Account ID sign in, anonymous accounts,
  verification resend, password reset, sign out everywhere, delete account,
  Turnstile-required handling.
- `PeerService` – `POST /api/v1/wg/peers` (with Shield policy and display-only
  device details), `PATCH` Shield, `DELETE` on disconnect, peer list for
  Shield counts, billing status. Plan errors (`subscription_required`, device
  limit) become plain sentences.
- `VPNProfile` / `WgQuickConfig` – validated tunnel profile (keys, preshared
  key, CIDRs, DNS, MTU 1280, keepalive 25, Exclude-LAN list identical to
  Android).
- `EndpointSelector` – LAN/WAN endpoint choice and roam order (Android port).
- `HeroLogic` – hero phases, one-tap action, pulse and mark ramp maths.
- `Redaction` – scrubs tokens, keys, IPv4/IPv6 from support reports.

### App ↔ extension

- The app generates the WireGuard key pair with CryptoKit (Curve25519), calls
  peer create, builds a `VPNProfile` and writes it to the **shared Keychain**
  access group (`VERITAS_KEYCHAIN_GROUP`), never to `providerConfiguration`.
- The system VPN configuration (`NETunnelProviderManager`) only holds the name
  "VeritasVPN" and the extension bundle id. On-demand is off: VeritasVPN never
  connects or switches networks without the user.
- The extension reads the profile, builds a WireGuardKit
  `TunnelConfiguration` (including the preshared key) and starts
  `WireGuardAdapter`. Failures are fixed `TunnelFailure` codes
  (`cloud.veritasvpn.tunnel` domain) that the app reads with
  `fetchLastDisconnectError` and shows as sentences.
- `handleAppMessage` only returns `RuntimeStats` JSON (bytes, last handshake),
  never the UAPI text, which contains keys.
- `EndpointRoamer` re-checks the LAN vs WAN endpoint after a network change and
  keeps whichever completes a fresh handshake. It never changes transport.

## Done vs stubbed

| Area | State |
| --- | --- |
| Sign in / sign up / Account ID / anonymous / verify email / reset password | Done (Turnstile in `WKWebView`) |
| Dashboard, logo hero connect animation (pulse, mark ramp, Reduce Motion) | Done |
| One-tap connect, plan check, "Sign in again" signal, live stats | Done |
| Direct (UDP) WireGuard tunnel with preshared key | Done, untested on device |
| LAN/WAN endpoint handling | Done, untested on device |
| Veritas Shield toggles + blocked counts (today / week, UTC) | Done |
| Split tunnel: allow local network (Exclude LAN) | Done |
| Split tunnel: per-app bypass | Not possible for App Store apps (Apple allows per-app VPN only on managed devices); the screen says so |
| Stealth (WireGuard over TLS) | **Stub.** Choosing "Stealth always" shows "Stealth is coming soon on iPhone, iPad and Mac…" and does not connect. No auto-switching |
| Network map (Asunción, Paraguay only, no location permission) | Done |
| Help & Support, diagnostics (redacted), privacy summary, terms, acknowledgements | Done |
| Plans / account / purchase history / delete account | Done |
| In-app purchase | **Placeholder.** StoreKit 2 loads `premium_monthly` / `premium_annual` prices if they exist; Subscribe is disabled with a "coming soon" note (`SubscriptionStore`, `TODO(apple-billing)`). No Bitcoin or sideload flows on Apple platforms |
| Shield block notifications (Android "Notices") | Not built (needs a DNS proxy in the extension) |
| Kill switch (Android always-on / lockdown) | Not built. Possible later with `includeAllNetworks` |
| es-419 | Done for every app string (`Scripts/es-419.json`, checked in CI) |
| Server changes | None |

## Privacy rules kept

- No location permission or location APIs anywhere (the map is static).
- No client-side IP logging. The extension drops wireguard-go log lines; the
  only use of local interface addresses is the in-memory LAN /24 comparison.
- Support reports are redacted and only sent when the user opens Mail.
- The privacy summary shows only "Asunción, Paraguay" and
  contact@veritasvpn.cloud.

## Building

CI (`macos-15`): `swift test` for VeritasCore, `Scripts/l10n.py --check`,
`xcodegen generate`, then `xcodebuild` for the iOS Simulator and macOS with
`CODE_SIGNING_ALLOWED=NO`.

Locally on a Mac with Xcode 16 (not on the user's MacBook):

```sh
brew install xcodegen go   # Go 1.26+
cd clients/apple
xcodegen generate
open VeritasVPN.xcodeproj
```

The bridge targets run `Scripts/build-wireguard-go-bridge.sh`, which needs Go
on `PATH` (or `VERITAS_GO_BIN`). If the WireGuardKit Go runtime patch does not
apply to the installed Go, the script warns and builds without it.

## When the Apple Developer account exists

1. **Team ID.** Copy `Config/Signing.local.xcconfig.example` to
   `Config/Signing.local.xcconfig` (git-ignored) and set
   `VERITAS_DEVELOPMENT_TEAM`. Change `VERITAS_BUNDLE_ID_PREFIX` only if
   `cloud.veritasvpn` is not available.
2. **Identifiers** (developer.apple.com → Identifiers), for
   `cloud.veritasvpn.vpn` and `cloud.veritasvpn.vpn.tunnel`, enable:
   - **Network Extensions** (the entitlement value is
     `packet-tunnel-provider`; already in the four `.entitlements` files).
   - **App Groups**: iOS `group.cloud.veritasvpn.vpn`; macOS uses
     `<TEAMID>.cloud.veritasvpn.vpn` (Team-prefixed group, required for the
     sandboxed Mac app).
   - **Keychain Sharing**: access group `<TEAMID>.cloud.veritasvpn.vpn.shared`
     on the app and the extension (this is where the tunnel profile lives).
3. **Provisioning.** Automatic signing is configured; open the project in
   Xcode, pick the team, and let Xcode create the four profiles (iOS app,
   iOS extension, Mac app, Mac extension).
4. **First device run.** Connect once on a real iPhone and a Mac (the iOS
   Simulator cannot run packet tunnels). Check: the system VPN prompt,
   handshake within 25 s, Shield toggle while connected, sign-out deletes the
   peer, LAN/WAN switch at home.
5. **App Store Connect.** Create one app record (universal purchase) and, when
   billing is ready, an auto-renewable subscription group with
   `premium_monthly` and `premium_annual`. Server-side App Store verification
   is still to be designed; then flip `SubscriptionStore.purchasesEnabled`
   and finish the `TODO(apple-billing)` items.
6. **Export compliance.** The app uses standard encryption (WireGuard, TLS);
   answer the App Store Connect encryption questions accordingly.
7. **Nothing is signed, uploaded, tagged or released without the owner's OK.**

## Known risks

- The code has not been compiled on the Linux box (no Xcode). CI is the first
  compile; expect small fixes.
- The WireGuardKit Go bridge for the arm64 iOS Simulator and newer Go versions
  is the most fragile build step. CI uses Go 1.26 (the repo-wide version).
- WireGuardKit is vendored in `clients/apple/Packages/WireGuardKit` (1.0.16-27 with a
  tools-version 5.5 manifest and bumped Go deps); see its README.
- The world map is an SVG in the asset catalog (vector preserved); check its
  rendering on device.
- Turnstile inside `WKWebView` has to be tested against the live page.
