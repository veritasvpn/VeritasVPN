import Foundation

/// Port of android/.../vpn/EndpointSelector.kt. Chooses between the
/// server's LAN endpoint (same network as the node, UDP 51820) and its WAN
/// endpoint. Never invents a port.
public enum EndpointSelector {
    public static func host(_ endpoint: String) -> String {
        let trimmed = endpoint.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return "" }
        if trimmed.hasPrefix("[") {
            if let end = trimmed.firstIndex(of: "]"), trimmed.distance(from: trimmed.startIndex, to: end) > 1 {
                return String(trimmed[trimmed.index(after: trimmed.startIndex)..<end])
            }
            return trimmed
        }
        guard let colon = trimmed.lastIndex(of: ":"), colon != trimmed.startIndex else { return trimmed }
        let port = trimmed[trimmed.index(after: colon)...]
        if !port.isEmpty, port.allSatisfy(\.isNumber) {
            return String(trimmed[..<colon])
        }
        return trimmed
    }

    public static func ipv4Octets(_ host: String) -> [Int]? {
        let parts = host.trimmingCharacters(in: .whitespaces).split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        var octets: [Int] = []
        for part in parts {
            guard !part.isEmpty, part.allSatisfy(\.isNumber), let value = Int(part), (0...255).contains(value) else { return nil }
            octets.append(value)
        }
        return octets
    }

    public static func sameIPv4Slash24(_ a: String, _ b: String) -> Bool {
        guard let left = ipv4Octets(a), let right = ipv4Octets(b) else { return false }
        return left[0] == right[0] && left[1] == right[1] && left[2] == right[2]
    }

    public static func isRFC1918(_ host: String) -> Bool {
        guard let o = ipv4Octets(host) else { return false }
        return o[0] == 10 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && (16...31).contains(o[1]))
    }

    private static func clean(_ value: String?) -> String {
        (value ?? "").trimmingCharacters(in: .whitespaces)
    }

    /// Endpoints to try after a network change: WAN first, then the current
    /// one, then LAN. Same-/24 is not proof of the node LAN, so this is the
    /// order to probe, keeping whichever completes a handshake.
    public static func probeOrder(current: String, lan: String?, wan: String?) -> [String] {
        let currentEp = clean(current)
        let wanEp = clean(wan)
        var lanEp = clean(lan)
        if lanEp.isEmpty, isRFC1918(host(currentEp)) { lanEp = currentEp }
        var seen = Set<String>()
        return [wanEp, currentEp, lanEp].filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    /// Prefer the exact LAN endpoint when an underlay IPv4 shares its /24,
    /// otherwise the WAN endpoint.
    public static func choose(current: String, lan: String?, wan: String?, underlayIPv4s: [String], allowSwitchToLan: Bool = true) -> String {
        let currentEp = clean(current)
        let wanEp = clean(wan)
        var lanEp = clean(lan)
        if lanEp.isEmpty, isRFC1918(host(currentEp)) { lanEp = currentEp }
        if underlayIPv4s.isEmpty { return currentEp }
        let lanHost = host(lanEp)
        let onLan = !lanHost.isEmpty && underlayIPv4s.contains { sameIPv4Slash24($0, lanHost) }
        let keepLan = !lanEp.isEmpty && (currentEp == lanEp || currentEp.isEmpty)
        if onLan, !lanEp.isEmpty, allowSwitchToLan || keepLan { return lanEp }
        if !wanEp.isEmpty { return wanEp }
        return currentEp
    }

    /// The order the tunnel tries after a path change: the preferred
    /// endpoint for this network first, then the rest of `probeOrder`.
    public static func roamOrder(current: String, lan: String?, wan: String?, underlayIPv4s: [String]) -> [String] {
        let preferred = choose(current: current, lan: lan, wan: wan, underlayIPv4s: underlayIPv4s)
        var seen = Set<String>()
        return ([preferred] + probeOrder(current: current, lan: lan, wan: wan)).filter { !$0.isEmpty && seen.insert($0).inserted }
    }
}
