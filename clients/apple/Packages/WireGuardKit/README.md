# WireGuardKit (vendored)

Source: https://git.zx2c4.com/wireguard-apple at
`2fec12a6e1f6e3460b6ee483aa00ad29cddadab1` (tag 1.0.16-27), MIT licence
(`COPYING`). Only `Sources/WireGuardKit`, `Sources/WireGuardKitC` and
`Sources/WireGuardKitGo` are included.

Why vendored: the upstream `Package.swift` declares `swift-tools-version:5.3`
but uses `.macOS(.v12)` / `.iOS(.v15)`, which current SwiftPM (Xcode 16)
rejects, so the remote package cannot be resolved.

Local changes:

- `Package.swift`: tools version 5.5 (same targets and settings).
- `Sources/WireGuardKitGo/go.mod` / `go.sum`: wireguard-go
  `v0.0.0-20231211153847-12269c276173`, `golang.org/x/{crypto,net,sys}`
  raised to versions without known high/critical advisories (same as the
  rest of the repo). Needs Go 1.26+.

- `Sources/WireGuardKitC/WireGuardKitC.h`: `#include <sys/types.h>` (needed
  for `u_int32_t`/`u_char` with explicit modules on Xcode 16).

The Swift sources are unchanged.
