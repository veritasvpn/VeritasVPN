import Foundation

/// Stable failure codes the packet tunnel reports to the app. The app shows
/// only `userMessage`, never the code.
public enum TunnelFailure: Int, Error, CaseIterable, Sendable, UserVisibleError {
    case missingProfile = 1
    case invalidConfig = 2
    case stealthComingSoon = 3
    case dnsResolution = 4
    case networkSettings = 5
    case backend = 6
    case handshakeTimeout = 7

    public static let domain = "cloud.veritasvpn.tunnel"

    public var nsError: NSError {
        NSError(domain: Self.domain, code: rawValue, userInfo: [NSLocalizedDescriptionKey: userMessage])
    }

    public init?(error: Error) {
        if let failure = error as? TunnelFailure { self = failure; return }
        let ns = error as NSError
        guard ns.domain == Self.domain, let failure = TunnelFailure(rawValue: ns.code) else { return nil }
        self = failure
    }

    public var userMessage: String {
        switch self {
        case .missingProfile, .backend: return Messages.tunnelStartFailed
        case .invalidConfig: return Messages.invalidConfig
        case .stealthComingSoon: return Messages.stealthComingSoon
        case .dnsResolution: return Messages.tunnelDnsFailed
        case .networkSettings: return Messages.tunnelStartFailed
        case .handshakeTimeout: return Messages.handshakeTimeout
        }
    }
}

/// App ↔ extension messages over `sendProviderMessage`.
public enum TunnelMessage: UInt8, Sendable {
    /// Reply: JSON `RuntimeStats`.
    case runtimeStats = 1
}

/// Counters and handshake age only. The extension never returns the UAPI
/// text itself, because it contains keys and endpoints.
public struct RuntimeStats: Codable, Equatable, Sendable {
    public var rxBytes: UInt64
    public var txBytes: UInt64
    /// Seconds since 1970 of the latest handshake, 0 when none yet.
    public var lastHandshakeEpoch: TimeInterval

    public init(rxBytes: UInt64 = 0, txBytes: UInt64 = 0, lastHandshakeEpoch: TimeInterval = 0) {
        self.rxBytes = rxBytes
        self.txBytes = txBytes
        self.lastHandshakeEpoch = lastHandshakeEpoch
    }

    public var hasHandshake: Bool { lastHandshakeEpoch > 0 }

    /// Parses wireguard-go's UAPI `get=1` answer. Counters are summed over
    /// peers; the newest handshake wins.
    public static func parse(uapi: String) -> RuntimeStats {
        var stats = RuntimeStats()
        var seconds: TimeInterval = 0
        var nanos: TimeInterval = 0
        func flushHandshake() {
            let value = seconds + nanos / 1_000_000_000
            if seconds > 0, value > stats.lastHandshakeEpoch { stats.lastHandshakeEpoch = value }
            seconds = 0
            nanos = 0
        }
        for line in uapi.split(whereSeparator: \.isNewline) {
            guard let eq = line.firstIndex(of: "=") else { continue }
            let key = line[..<eq]
            let value = line[line.index(after: eq)...]
            switch key {
            case "public_key":
                flushHandshake()
            case "rx_bytes":
                stats.rxBytes &+= UInt64(value) ?? 0
            case "tx_bytes":
                stats.txBytes &+= UInt64(value) ?? 0
            case "last_handshake_time_sec":
                seconds = TimeInterval(value) ?? 0
            case "last_handshake_time_nsec":
                nanos = TimeInterval(value) ?? 0
            default:
                continue
            }
        }
        flushHandshake()
        return stats
    }
}

/// Names shared by the app and the extension. Values come from Info.plist
/// keys filled by build settings (see Config/Signing.xcconfig).
public struct SharedConfiguration: Sendable, Equatable {
    public var appGroup: String
    public var keychainGroup: String
    public var tunnelBundleIdentifier: String

    public static let profileService = "cloud.veritasvpn.tunnel"
    public static let profileAccount = "active-profile"

    public init(appGroup: String, keychainGroup: String, tunnelBundleIdentifier: String) {
        self.appGroup = appGroup
        self.keychainGroup = keychainGroup
        self.tunnelBundleIdentifier = tunnelBundleIdentifier
    }

    public static func fromBundle(_ bundle: Bundle = .main) -> SharedConfiguration {
        func value(_ key: String) -> String {
            let raw = (bundle.object(forInfoDictionaryKey: key) as? String ?? "").trimmingCharacters(in: .whitespaces)
            // Unexpanded settings, or a group built without a Team ID prefix
            // (".cloud.veritasvpn…" in unsigned builds), mean "not configured".
            return raw.contains("$(") || raw.hasPrefix(".") ? "" : raw
        }
        return SharedConfiguration(
            appGroup: value("VeritasAppGroup"),
            keychainGroup: value("VeritasKeychainGroup"),
            tunnelBundleIdentifier: value("VeritasTunnelBundleID")
        )
    }
}

#if canImport(Security)
/// The tunnel profile in the shared Keychain access group.
public struct ProfileVault: Sendable {
    private let item: KeychainItem

    public init(configuration: SharedConfiguration) {
        item = KeychainItem(
            service: SharedConfiguration.profileService,
            account: SharedConfiguration.profileAccount,
            accessGroup: configuration.keychainGroup.isEmpty ? nil : configuration.keychainGroup
        )
    }

    public func load() -> VPNProfile? { item.readValue(VPNProfile.self) }

    @discardableResult
    public func save(_ profile: VPNProfile) -> Bool { item.writeValue(profile) }

    public func clear() { item.delete() }
}
#endif
