import Foundation

/// wg-quick text for a profile, and the reverse. Used for config parsing
/// tests and for importing a profile; never written to logs.
public enum WgQuickConfig {
    public static func render(_ profile: VPNProfile) -> String {
        var lines: [String] = []
        lines.append("[Interface]")
        lines.append("PrivateKey = \(profile.privateKey)")
        lines.append("Address = \(profile.normalizedAddresses.joined(separator: ", "))")
        lines.append("DNS = \(profile.dns.joined(separator: ", "))")
        lines.append("MTU = \(profile.mtu)")
        lines.append("")
        lines.append("[Peer]")
        lines.append("PublicKey = \(profile.serverPublicKey)")
        if let psk = profile.presharedKey, !psk.isEmpty {
            lines.append("PresharedKey = \(psk)")
        }
        lines.append("Endpoint = \(profile.endpoint)")
        lines.append("AllowedIPs = \(profile.allowedIPs.joined(separator: ", "))")
        lines.append("PersistentKeepalive = \(profile.persistentKeepalive)")
        return lines.joined(separator: "\n") + "\n"
    }

    /// Parses one [Interface] and one [Peer]. Unknown keys are ignored;
    /// a missing required key or an invalid value throws `ProfileError`.
    public static func parse(_ text: String, peerId: String = "") throws -> VPNProfile {
        enum Section { case none, interface, peer }
        var section = Section.none
        var interface: [String: String] = [:]
        var peer: [String: String] = [:]
        var peerCount = 0
        for rawLine in text.split(whereSeparator: \.isNewline) {
            var line = String(rawLine)
            if let hash = line.firstIndex(of: "#") { line = String(line[..<hash]) }
            line = line.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { continue }
            let lower = line.lowercased()
            if lower == "[interface]" { section = .interface; continue }
            if lower == "[peer]" { section = .peer; peerCount += 1; continue }
            guard let eq = line.firstIndex(of: "=") else { throw ProfileError.invalidAddress }
            let key = line[..<eq].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: eq)...].trimmingCharacters(in: .whitespaces)
            switch section {
            case .interface: interface[key] = appended(interface[key], value)
            case .peer where peerCount == 1: peer[key] = appended(peer[key], value)
            default: continue
            }
        }
        func list(_ value: String?) -> [String] {
            (value ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        }
        guard let privateKey = interface["privatekey"] else { throw ProfileError.invalidKey(field: "private") }
        guard let publicKey = peer["publickey"] else { throw ProfileError.invalidKey(field: "public") }
        guard let endpoint = peer["endpoint"] else { throw ProfileError.invalidEndpoint }
        let dns = list(interface["dns"])
        guard !dns.isEmpty else { throw ProfileError.missingDNS }
        let mtu = interface["mtu"].flatMap { Int($0) } ?? VPNProfile.defaultMTU
        let keepalive = peer["persistentkeepalive"].flatMap { Int($0) } ?? 0
        let psk = peer["presharedkey"].flatMap { $0.isEmpty ? nil : $0 }
        let profile = VPNProfile(
            peerId: peerId,
            privateKey: privateKey,
            addresses: list(interface["address"]),
            dns: dns,
            mtu: mtu,
            serverPublicKey: publicKey,
            presharedKey: psk,
            endpoint: endpoint,
            allowedIPs: list(peer["allowedips"]),
            persistentKeepalive: keepalive
        )
        try profile.validate()
        return profile
    }

    private static func appended(_ existing: String?, _ value: String) -> String {
        guard let existing, !existing.isEmpty else { return value }
        return existing + ", " + value
    }
}

/// Port of `normalize_path_prefix` (veritas-core): the wstunnel upgrade
/// path secret is one bare segment of `[A-Za-z0-9-_.~]`, at most 128 bytes,
/// never `..`. One optional leading `/` from older configs is accepted.
public enum StealthPath {
    public static func normalizePrefix(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let segment = trimmed.hasPrefix("/") ? String(trimmed.dropFirst()) : trimmed
        guard !segment.isEmpty, segment.utf8.count <= 128, !segment.contains("..") else { return nil }
        let allowed = segment.utf8.allSatisfy { byte in
            (byte >= 0x30 && byte <= 0x39) || (byte >= 0x41 && byte <= 0x5A) || (byte >= 0x61 && byte <= 0x7A)
                || byte == 0x2D || byte == 0x5F || byte == 0x2E || byte == 0x7E
        }
        return allowed ? segment : nil
    }
}
