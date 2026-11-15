# VeritasVPN Apple clients (iOS, iPadOS, macOS)

SwiftUI app + Network Extension packet tunnel (WireGuardKit), modelled on the
Android app. See [docs/APPLE_CLIENTS.md](../../docs/APPLE_CLIENTS.md) for the
architecture, what is done, what is stubbed, and the Apple Developer account
steps.

```sh
# Unit tests (any Mac)
cd Packages/VeritasCore && swift test

# Strings (en + es-419)
python3 Scripts/l10n.py          # regenerate
python3 Scripts/l10n.py --check  # CI check

# Xcode project
brew install xcodegen go@1.22
xcodegen generate && open VeritasVPN.xcodeproj
```

Builds are unsigned until a Team ID is set in `Config/Signing.local.xcconfig`.
