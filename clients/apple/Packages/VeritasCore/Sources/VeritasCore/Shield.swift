import Foundation

/// Veritas Shield DNS filters. Defaults match Android's ShieldPolicy.
public struct ShieldPolicy: Codable, Equatable, Sendable {
    public var blockMalicious: Bool
    public var blockAds: Bool
    public var blockAdult: Bool
    public var blockTrackers: Bool

    public init(blockMalicious: Bool = true, blockAds: Bool = false, blockAdult: Bool = false, blockTrackers: Bool = true) {
        self.blockMalicious = blockMalicious
        self.blockAds = blockAds
        self.blockAdult = blockAdult
        self.blockTrackers = blockTrackers
    }

    /// The `shield` object for peer create (POST) and update (PATCH).
    public var wireBody: JSONValue {
        [
            "shield": [
                "block_malicious": .bool(blockMalicious),
                "block_ads": .bool(blockAds),
                "block_adult": .bool(blockAdult),
                "block_trackers": .bool(blockTrackers),
            ],
        ]
    }
}

public enum ShieldGroup: String, CaseIterable, Sendable {
    case malicious, trackers, ads, adult

    /// English label; the app localizes it.
    public var label: String {
        switch self {
        case .malicious: return "Malicious"
        case .trackers: return "Trackers"
        case .ads: return "Ads"
        case .adult: return "Adult"
        }
    }

    public func enabled(in policy: ShieldPolicy) -> Bool {
        switch self {
        case .malicious: return policy.blockMalicious
        case .trackers: return policy.blockTrackers
        case .ads: return policy.blockAds
        case .adult: return policy.blockAdult
        }
    }

    public func count(_ counts: ShieldCategoryCounts) -> Int64 {
        switch self {
        case .malicious: return counts.maliciousTotal
        case .trackers: return counts.trackers
        case .ads: return counts.ads
        case .adult: return counts.adult
        }
    }
}

public struct ShieldRow: Equatable, Sendable, Identifiable {
    public var group: ShieldGroup
    public var enabled: Bool
    public var today: Int64
    public var week: Int64
    public var id: String { group.rawValue }

    /// One row per group. A group that is off never reads as blocking.
    public static func rows(policy: ShieldPolicy, blocks: ShieldBlocks?) -> [ShieldRow] {
        ShieldGroup.allCases.map { group in
            let on = group.enabled(in: policy)
            return ShieldRow(
                group: group,
                enabled: on,
                today: on ? group.count(blocks?.today ?? .init()) : 0,
                week: on ? group.count(blocks?.week ?? .init()) : 0
            )
        }
    }
}
