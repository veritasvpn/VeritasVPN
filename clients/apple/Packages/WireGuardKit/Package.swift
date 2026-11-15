// swift-tools-version:5.5
// Vendored copy of WireGuardKit from https://git.zx2c4.com/wireguard-apple
// at 2fec12a6e1f6e3460b6ee483aa00ad29cddadab1 (tag 1.0.16-27), MIT (COPYING).
// Local changes (see README.md): tools-version 5.5 so current SwiftPM accepts
// the .macOS(.v12)/.iOS(.v15) platforms, and updated Go module versions.

import PackageDescription

let package = Package(
    name: "WireGuardKit",
    platforms: [
        .macOS(.v12),
        .iOS(.v15),
    ],
    products: [
        .library(name: "WireGuardKit", targets: ["WireGuardKit"]),
    ],
    dependencies: [],
    targets: [
        .target(
            name: "WireGuardKit",
            dependencies: ["WireGuardKitGo", "WireGuardKitC"]
        ),
        .target(
            name: "WireGuardKitC",
            dependencies: [],
            publicHeadersPath: "."
        ),
        .target(
            name: "WireGuardKitGo",
            dependencies: [],
            exclude: [
                "goruntime-boottime-over-monotonic.diff",
                "go.mod",
                "go.sum",
                "api-apple.go",
                "Makefile",
            ],
            publicHeadersPath: ".",
            linkerSettings: [.linkedLibrary("wg-go")]
        ),
    ]
)
