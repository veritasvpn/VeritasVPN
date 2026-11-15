// swift-tools-version:5.9
import PackageDescription

// Platform-independent VeritasVPN logic shared by the iOS and macOS apps and
// their packet tunnel extensions: API contract, auth with single-flight
// refresh, user-facing error text, tunnel profile parsing and endpoint choice.
// It only depends on Foundation (and CryptoKit/Security where available) so
// `swift test` runs it without Xcode project setup.
let package = Package(
    name: "VeritasCore",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "VeritasCore", targets: ["VeritasCore"]),
    ],
    targets: [
        .target(name: "VeritasCore"),
        .testTarget(name: "VeritasCoreTests", dependencies: ["VeritasCore"]),
    ]
)
