import Foundation

/// How WireGuard is carried. Mirrors Android's Stealth setting.
public enum StealthMode: String, Codable, CaseIterable, Sendable {
    /// Direct UDP. On Android a blocked network suggests Stealth; the Apple
    /// clients never switch on their own.
    case auto
    case udpOnly = "udp_only"
    case stealthAlways = "stealth_always"
}

public enum TunnelTransport: String, Codable, Sendable {
    case direct
    case stealth
}

public enum ProfileError: Error, Equatable, Sendable {
    case missingDNS
    case invalidKey(field: String)
    case invalidAddress
    case invalidEndpoint
    case invalidAllowedIP
    case invalidDNS
    case invalidPathPrefix
}

/// Everything the packet tunnel needs, written by the app to the shared
/// Keychain and read by the extension. Never logged and never shown.
public struct VPNProfile: Codable, Equatable, Sendable {
    public var peerId: String
    public var privateKey: String
    public var addresses: [String]
    public var dns: [String]
    public var mtu: Int
    public var serverPublicKey: String
    public var presharedKey: String?
    public var endpoint: String
    public var lanEndpoint: String?
    public var wanEndpoint: String?
    public var allowedIPs: [String]
    public var persistentKeepalive: Int
    public var transport: TunnelTransport

    public init(
        peerId: String,
        privateKey: String,
        addresses: [String],
        dns: [String],
        mtu: Int = VPNProfile.defaultMTU,
        serverPublicKey: String,
        presharedKey: String?,
        endpoint: String,
        lanEndpoint: String? = nil,
        wanEndpoint: String? = nil,
        allowedIPs: [String],
        persistentKeepalive: Int = 25,
        transport: TunnelTransport = .direct
    ) {
        self.peerId = peerId
        self.privateKey = privateKey
        self.addresses = addresses
        self.dns = dns
        self.mtu = mtu
        self.serverPublicKey = serverPublicKey
        self.presharedKey = presharedKey
        self.endpoint = endpoint
        self.lanEndpoint = lanEndpoint
        self.wanEndpoint = wanEndpoint
        self.allowedIPs = allowedIPs
        self.persistentKeepalive = persistentKeepalive
        self.transport = transport
    }

    /// Product default MTU (docs/MTU_STRATEGY.md), same as Android.
    public static let defaultMTU = 1280

    /// Builds and validates the profile from a fresh peer. Same rules as
    /// Android's `WireGuardSession.buildConfig`: the server must send a DNS
    /// gateway (no silent fallback to public DNS), the preshared key is used
    /// whenever present, and exclude-LAN only replaces `0.0.0.0/0`.
    public static func make(peer: PeerResponse, privateKey: String, excludeLAN: Bool) throws -> VPNProfile {
        let dns = (peer.dnsServer ?? "")
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        guard !dns.isEmpty else { throw ProfileError.missingDNS }
        let serverAllowed = peer.clientAllowedIps ?? peer.allowedIps ?? ["0.0.0.0/0", "::/0"]
        let psk = peer.presharedKey?.trimmingCharacters(in: .whitespacesAndNewlines)
        let addresses = peer.assignedIp
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        let profile = VPNProfile(
            peerId: peer.peerId,
            privateKey: privateKey,
            addresses: addresses,
            dns: dns,
            serverPublicKey: peer.serverPublicKey.trimmingCharacters(in: .whitespacesAndNewlines),
            presharedKey: (psk?.isEmpty ?? true) ? nil : psk,
            endpoint: peer.serverEndpoint.trimmingCharacters(in: .whitespaces),
            lanEndpoint: nonEmpty(peer.serverEndpointLan),
            wanEndpoint: nonEmpty(peer.serverEndpointWan),
            allowedIPs: LANExclusion.resolveAllowedIPs(serverAllowed, excludeLAN: excludeLAN)
        )
        try profile.validate()
        return profile
    }

    private static func nonEmpty(_ value: String?) -> String? {
        let trimmed = (value ?? "").trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? nil : trimmed
    }

    public func validate() throws {
        guard WireGuardKey.isValid(privateKey) else { throw ProfileError.invalidKey(field: "private") }
        guard WireGuardKey.isValid(serverPublicKey) else { throw ProfileError.invalidKey(field: "public") }
        if let presharedKey, !WireGuardKey.isValid(presharedKey) { throw ProfileError.invalidKey(field: "preshared") }
        guard !addresses.isEmpty, addresses.allSatisfy({ CIDR.parse($0, defaultHostPrefix: true) != nil }) else {
            throw ProfileError.invalidAddress
        }
        guard !dns.isEmpty, dns.allSatisfy({ CIDR.isIPAddress($0) }) else { throw ProfileError.invalidDNS }
        guard Endpoint.isValid(endpoint) else { throw ProfileError.invalidEndpoint }
        if let lanEndpoint, !Endpoint.isValid(lanEndpoint) { throw ProfileError.invalidEndpoint }
        if let wanEndpoint, !Endpoint.isValid(wanEndpoint) { throw ProfileError.invalidEndpoint }
        guard !allowedIPs.isEmpty, allowedIPs.allSatisfy({ CIDR.parse($0, defaultHostPrefix: false) != nil }) else {
            throw ProfileError.invalidAllowedIP
        }
        guard (576...9000).contains(mtu) else { throw ProfileError.invalidAddress }
    }

    /// Addresses with an explicit prefix (`/32` or `/128` when the server sent a bare IP).
    public var normalizedAddresses: [String] {
        addresses.compactMap { CIDR.parse($0, defaultHostPrefix: true)?.description }
    }

    public func with(endpoint newEndpoint: String) -> VPNProfile {
        var copy = self
        copy.endpoint = newEndpoint
        return copy
    }
}

public enum WireGuardKey {
    /// A WireGuard key is 32 bytes in standard base64 (44 characters).
    public static func isValid(_ base64: String) -> Bool {
        let trimmed = base64.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count == 44, let data = Data(base64Encoded: trimmed) else { return false }
        return data.count == 32
    }
}

public struct CIDR: Equatable, CustomStringConvertible, Sendable {
    public let address: String
    public let prefix: Int
    public let isIPv6: Bool

    public var description: String { "\(address)/\(prefix)" }

    public static func isIPv4(_ value: String) -> Bool { EndpointSelector.ipv4Octets(value) != nil }

    public static func isIPv6(_ value: String) -> Bool {
        var storage = in6_addr()
        return value.withCString { inet_pton(AF_INET6, $0, &storage) } == 1
    }

    public static func isIPAddress(_ value: String) -> Bool { isIPv4(value) || isIPv6(value) }

    /// `defaultHostPrefix` turns a bare address into a host route.
    public static func parse(_ text: String, defaultHostPrefix: Bool) -> CIDR? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        let pieces = trimmed.split(separator: "/", omittingEmptySubsequences: false)
        guard pieces.count == 1 || pieces.count == 2 else { return nil }
        let address = String(pieces[0])
        let v6: Bool
        if isIPv4(address) { v6 = false } else if isIPv6(address) { v6 = true } else { return nil }
        let maxPrefix = v6 ? 128 : 32
        if pieces.count == 1 {
            return defaultHostPrefix ? CIDR(address: address, prefix: maxPrefix, isIPv6: v6) : nil
        }
        let prefixText = pieces[1]
        guard !prefixText.isEmpty, prefixText.allSatisfy(\.isNumber), let prefix = Int(prefixText), (0...maxPrefix).contains(prefix) else {
            return nil
        }
        return CIDR(address: address, prefix: prefix, isIPv6: v6)
    }
}

public enum Endpoint {
    /// `host:port` or `[v6]:port` with port 1...65535 and a non-empty host.
    public static func isValid(_ endpoint: String) -> Bool {
        let trimmed = endpoint.trimmingCharacters(in: .whitespaces)
        guard let colon = trimmed.lastIndex(of: ":") else { return false }
        let portText = trimmed[trimmed.index(after: colon)...]
        guard !portText.isEmpty, portText.allSatisfy(\.isNumber), let port = Int(portText), (1...65535).contains(port) else {
            return false
        }
        let host = String(trimmed[..<colon])
        if host.hasPrefix("[") {
            guard host.hasSuffix("]"), host.count > 2 else { return false }
            return CIDR.isIPv6(String(host.dropFirst().dropLast()))
        }
        guard !host.isEmpty, !host.contains(":"), !host.contains(" ") else { return false }
        return true
    }
}

/// Android's exclude-LAN list (VpnSettings.EXCLUDE_LAN_ALLOWED_IPS): all IPv4
/// unicast except the RFC 1918 private ranges.
public enum LANExclusion {
    public static let allowedIPs: [String] = [
        "0.0.0.0/5", "8.0.0.0/7", "11.0.0.0/8", "12.0.0.0/6", "16.0.0.0/4", "32.0.0.0/3",
        "64.0.0.0/2", "128.0.0.0/3", "160.0.0.0/5", "168.0.0.0/6", "172.0.0.0/12",
        "172.32.0.0/11", "172.64.0.0/10", "172.128.0.0/9", "173.0.0.0/8", "174.0.0.0/7",
        "176.0.0.0/4", "192.0.0.0/9", "192.128.0.0/11", "192.160.0.0/13", "192.169.0.0/16",
        "192.170.0.0/15", "192.172.0.0/14", "192.176.0.0/12", "192.192.0.0/10", "193.0.0.0/8",
        "194.0.0.0/7", "196.0.0.0/6", "200.0.0.0/5", "208.0.0.0/4",
    ]

    /// Replaces only `0.0.0.0/0` (other routes and `::/0` stay as sent).
    public static func resolveAllowedIPs(_ serverIPs: [String], excludeLAN: Bool) -> [String] {
        let cleaned = serverIPs.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        guard excludeLAN, cleaned.contains("0.0.0.0/0") else { return cleaned }
        return cleaned.filter { $0 != "0.0.0.0/0" } + allowedIPs
    }
}
