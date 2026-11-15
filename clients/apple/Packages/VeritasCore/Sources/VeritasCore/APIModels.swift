import Foundation

// Wire models for https://api.veritasvpn.cloud. Field names match the Android
// client (android/app/src/main/java/cloud/veritasvpn/api/ApiClient.kt). Every
// optional field decodes leniently so an additive server change never breaks
// sign-in or connect.

private extension KeyedDecodingContainer {
    func string(_ key: Key) -> String? { try? decodeIfPresent(String.self, forKey: key) }
    func bool(_ key: Key, default value: Bool = false) -> Bool { (try? decodeIfPresent(Bool.self, forKey: key)) ?? value }
    func int64(_ key: Key, default value: Int64 = 0) -> Int64 { (try? decodeIfPresent(Int64.self, forKey: key)) ?? value }
    func strings(_ key: Key) -> [String]? { try? decodeIfPresent([String].self, forKey: key) }
}

public struct AuthResponse: Decodable, Equatable, Sendable {
    public var accessToken: String
    public var refreshToken: String
    public var accountId: String
    public var email: String?
    public var verificationRequired: Bool

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case accountId = "account_id"
        case email
        case verificationRequired = "verification_required"
    }

    public init(accessToken: String, refreshToken: String, accountId: String, email: String? = nil, verificationRequired: Bool = false) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.accountId = accountId
        self.email = email
        self.verificationRequired = verificationRequired
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        accessToken = c.string(.accessToken) ?? ""
        refreshToken = c.string(.refreshToken) ?? ""
        accountId = c.string(.accountId) ?? ""
        email = c.string(.email)
        verificationRequired = c.bool(.verificationRequired)
    }
}

public struct AccountProfile: Decodable, Equatable, Sendable {
    public var accountId: String
    public var email: String?

    enum CodingKeys: String, CodingKey {
        case accountId = "account_id"
        case email
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        accountId = c.string(.accountId) ?? ""
        email = c.string(.email)
    }
}

/// `{"error": "..."}` or `{"message": "..."}` error bodies.
public struct ServerErrorBody: Decodable, Equatable, Sendable {
    public var error: String?
    public var message: String?
    public var code: String?
}

public struct PeerResponse: Decodable, Equatable, Sendable {
    public var peerId: String
    public var serverPublicKey: String
    public var serverEndpoint: String
    public var serverEndpointLan: String?
    public var serverEndpointWan: String?
    public var stealthEndpoint: String?
    public var stealthAvailable: Bool
    public var stealthPathPrefix: String?
    public var assignedIp: String
    public var dnsServer: String?
    public var presharedKey: String?
    public var clientAllowedIps: [String]?
    public var allowedIps: [String]?

    enum CodingKeys: String, CodingKey {
        case peerId = "peer_id"
        case serverPublicKey = "server_public_key"
        case serverEndpoint = "server_endpoint"
        case serverEndpointLan = "server_endpoint_lan"
        case serverEndpointWan = "server_endpoint_wan"
        case stealthEndpoint = "stealth_endpoint"
        case stealthAvailable = "stealth_available"
        case stealthPathPrefix = "stealth_path_prefix"
        case assignedIp = "assigned_ip"
        case dnsServer = "dns_server"
        case presharedKey = "preshared_key"
        case clientAllowedIps = "client_allowed_ips"
        case allowedIps = "allowed_ips"
    }

    public init(
        peerId: String,
        serverPublicKey: String,
        serverEndpoint: String,
        serverEndpointLan: String? = nil,
        serverEndpointWan: String? = nil,
        stealthEndpoint: String? = nil,
        stealthAvailable: Bool = false,
        stealthPathPrefix: String? = nil,
        assignedIp: String,
        dnsServer: String?,
        presharedKey: String?,
        clientAllowedIps: [String]? = nil,
        allowedIps: [String]? = nil
    ) {
        self.peerId = peerId
        self.serverPublicKey = serverPublicKey
        self.serverEndpoint = serverEndpoint
        self.serverEndpointLan = serverEndpointLan
        self.serverEndpointWan = serverEndpointWan
        self.stealthEndpoint = stealthEndpoint
        self.stealthAvailable = stealthAvailable
        self.stealthPathPrefix = stealthPathPrefix
        self.assignedIp = assignedIp
        self.dnsServer = dnsServer
        self.presharedKey = presharedKey
        self.clientAllowedIps = clientAllowedIps
        self.allowedIps = allowedIps
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // Required for a usable tunnel; a missing one fails decoding.
        peerId = try c.decode(String.self, forKey: .peerId)
        serverPublicKey = try c.decode(String.self, forKey: .serverPublicKey)
        serverEndpoint = try c.decode(String.self, forKey: .serverEndpoint)
        assignedIp = try c.decode(String.self, forKey: .assignedIp)
        serverEndpointLan = c.string(.serverEndpointLan)
        serverEndpointWan = c.string(.serverEndpointWan)
        stealthEndpoint = c.string(.stealthEndpoint)
        stealthAvailable = c.bool(.stealthAvailable)
        stealthPathPrefix = c.string(.stealthPathPrefix)
        dnsServer = c.string(.dnsServer)
        presharedKey = c.string(.presharedKey)
        clientAllowedIps = c.strings(.clientAllowedIps)
        allowedIps = c.strings(.allowedIps)
    }
}

public struct PurchaseHistoryItem: Decodable, Equatable, Sendable {
    public var createdAt: String?
    public var amountCents: Int64
    public var currency: String?
    public var plan: String?
    public var status: String?
    public var provider: String?
    public var isTrial: Bool

    enum CodingKeys: String, CodingKey {
        case createdAt = "created_at"
        case amountCents = "amount_cents"
        case currency, plan, status, provider
        case isTrial = "is_trial"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        createdAt = c.string(.createdAt)
        amountCents = c.int64(.amountCents)
        currency = c.string(.currency)
        plan = c.string(.plan)
        status = c.string(.status)
        provider = c.string(.provider)
        isTrial = c.bool(.isTrial)
    }
}

public struct BillingStatus: Decodable, Equatable, Sendable {
    public var tier: String
    public var status: String
    public var paymentMethod: String
    public var planId: String?
    public var currentPeriodEnd: String?
    public var cancelAtPeriodEnd: Bool
    public var isPremium: Bool
    public var paymentState: String
    public var payments: [PurchaseHistoryItem]?
    public var trialEligible: Bool
    public var accessState: String?
    public var resumesAt: String?

    enum CodingKeys: String, CodingKey {
        case tier, status, payments
        case paymentMethod = "payment_method"
        case planId = "plan_id"
        case currentPeriodEnd = "current_period_end"
        case cancelAtPeriodEnd = "cancel_at_period_end"
        case isPremium = "is_premium"
        case paymentState = "payment_state"
        case trialEligible = "trial_eligible"
        case accessState = "access_state"
        case resumesAt = "resumes_at"
    }

    public init(isPremium: Bool, trialEligible: Bool = false) {
        tier = isPremium ? "premium" : "free"
        status = "active"
        paymentMethod = "none"
        planId = nil
        currentPeriodEnd = nil
        cancelAtPeriodEnd = false
        self.isPremium = isPremium
        paymentState = "none"
        payments = nil
        self.trialEligible = trialEligible
        accessState = nil
        resumesAt = nil
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        tier = c.string(.tier) ?? "free"
        status = c.string(.status) ?? "active"
        paymentMethod = c.string(.paymentMethod) ?? "none"
        planId = c.string(.planId)
        currentPeriodEnd = c.string(.currentPeriodEnd)
        cancelAtPeriodEnd = c.bool(.cancelAtPeriodEnd)
        isPremium = c.bool(.isPremium)
        paymentState = c.string(.paymentState) ?? "none"
        payments = try? c.decodeIfPresent([PurchaseHistoryItem].self, forKey: .payments)
        trialEligible = c.bool(.trialEligible)
        accessState = c.string(.accessState)
        resumesAt = c.string(.resumesAt)
    }

    /// Paused subscriptions keep the account but cannot connect.
    public var isPaused: Bool { accessState == "paused" || status == "paused" }

    public var isPaymentPending: Bool {
        ["awaiting_payment", "awaiting_confirmation", "checking"].contains(paymentState)
    }
}

public struct ShieldCategoryCounts: Decodable, Equatable, Sendable {
    public var malware: Int64 = 0
    public var phishing: Int64 = 0
    public var scam: Int64 = 0
    public var crypto: Int64 = 0
    public var trackers: Int64 = 0
    public var ads: Int64 = 0
    public var adult: Int64 = 0

    enum CodingKeys: String, CodingKey { case malware, phishing, scam, crypto, trackers, ads, adult }

    public init() {}

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        malware = c.int64(.malware)
        phishing = c.int64(.phishing)
        scam = c.int64(.scam)
        crypto = c.int64(.crypto)
        trackers = c.int64(.trackers)
        ads = c.int64(.ads)
        adult = c.int64(.adult)
    }

    public var maliciousTotal: Int64 { malware + phishing + scam + crypto }
}

public struct ShieldBlocks: Decodable, Equatable, Sendable {
    public var today: ShieldCategoryCounts
    public var week: ShieldCategoryCounts

    public init(today: ShieldCategoryCounts = .init(), week: ShieldCategoryCounts = .init()) {
        self.today = today
        self.week = week
    }

    enum CodingKeys: String, CodingKey { case today, week }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        today = (try? c.decodeIfPresent(ShieldCategoryCounts.self, forKey: .today)) ?? .init()
        week = (try? c.decodeIfPresent(ShieldCategoryCounts.self, forKey: .week)) ?? .init()
    }
}

public struct PeerInfo: Decodable, Equatable, Sendable {
    public var id: String
    public var status: String
    public var shieldPreset: String
    public var dnsBlockedCount: Int64
    public var shieldBlocks: ShieldBlocks?

    enum CodingKeys: String, CodingKey {
        case id, status
        case shieldPreset = "shield_preset"
        case dnsBlockedCount = "dns_blocked_count"
        case shieldBlocks = "shield_blocks"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.string(.id) ?? ""
        status = c.string(.status) ?? ""
        shieldPreset = c.string(.shieldPreset) ?? "standard"
        dnsBlockedCount = c.int64(.dnsBlockedCount)
        shieldBlocks = try? c.decodeIfPresent(ShieldBlocks.self, forKey: .shieldBlocks)
    }
}

public struct PeerListResponse: Decodable, Equatable, Sendable {
    public var peers: [PeerInfo]

    enum CodingKeys: String, CodingKey { case peers }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        peers = (try? c.decodeIfPresent([PeerInfo].self, forKey: .peers)) ?? []
    }
}
