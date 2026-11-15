import Foundation

/// Subscription products. Product ids match Google Play
/// (android/.../billing/PlayCatalog.kt and services/billing-svc play_products.go)
/// so App Store Connect can reuse them.
public enum PlanCatalog {
    public struct Plan: Equatable, Sendable, Identifiable {
        public let id: String
        public let productId: String
        public let displayName: String
        /// Fallback shown before the store answers (USD, same as Android).
        public let fallbackPrice: String
        public let periodDays: Int
    }

    public static let monthly = Plan(id: "premium_monthly", productId: "premium_monthly", displayName: "Monthly",
                                     fallbackPrice: "$3 / 30 days", periodDays: 30)
    public static let annual = Plan(id: "premium_annual", productId: "premium_annual", displayName: "Annual",
                                    fallbackPrice: "$30 / 365 days", periodDays: 365)
    public static let all = [monthly, annual]
    public static var productIds: [String] { all.map(\.productId) }

    public static func plan(id: String) -> Plan? { all.first { $0.id == id || $0.productId == id } }
}

/// Display-only device details for the account dashboard, same keys as
/// Android. No hardware identifiers, account data or location.
public struct DeviceMetadata: Equatable, Sendable {
    public var platform: String
    public var model: String
    public var osVersion: String
    public var clientVersion: String

    public init(platform: String, model: String, osVersion: String, clientVersion: String) {
        self.platform = platform
        self.model = String(model.prefix(100))
        self.osVersion = osVersion
        self.clientVersion = clientVersion
    }

    public var wireBody: JSONValue {
        [
            "device_platform": .string(platform),
            "device_model": .string(model),
            "device_os_version": .string(osVersion),
            "client_version": .string(clientVersion),
        ]
    }
}

/// Peer (device) calls on the wg-manager API.
public struct PeerService: Sendable {
    public let api: AuthorizedAPI

    public init(api: AuthorizedAPI) { self.api = api }

    /// POST /api/v1/wg/peers. The server upserts by (account, device_id).
    public func createPeer(publicKey: String, deviceId: String, metadata: DeviceMetadata, shield: ShieldPolicy) async throws -> PeerResponse {
        let body = JSONValue.object(["public_key": .string(publicKey), "device_id": .string(deviceId)])
            .merging(metadata.wireBody)
            .merging(shield.wireBody)
        let response = try await api.send(.post, "/api/v1/wg/peers", body: body, retry: .connectFailures)
        if !response.isSuccess {
            switch response.failure {
            case .serverUnavailable:
                throw APIError.serverUnavailable(status: response.status)
            default:
                throw PeerSetupError(status: response.status, serverMessage: response.serverMessage, code: response.serverCode)
            }
        }
        return try response.decode(PeerResponse.self)
    }

    /// Best-effort cleanup after disconnect. Never signs out.
    public func deletePeer(id: String) async {
        guard !id.isEmpty else { return }
        _ = try? await api.send(.delete, "/api/v1/wg/peers/\(id)", timeout: 8, background: true)
    }

    /// PATCH the live peer's Shield filters (user-initiated).
    public func updateShield(peerId: String, policy: ShieldPolicy) async throws {
        let response = try await api.send(.patch, "/api/v1/wg/peers/\(peerId)", body: policy.wireBody)
        try response.ensureSuccess()
    }

    /// Background poll of this device's Shield counters. Never signs out.
    public func peerInfo(id: String) async throws -> PeerInfo? {
        let response = try await api.send(.get, "/api/v1/wg/peers", timeout: 6, background: true)
        return try response.decode(PeerListResponse.self).peers.first { $0.id == id }
    }

    /// GET /api/v1/billing/status. Background: a failure never signs out.
    public func billingStatus(background: Bool = true) async throws -> BillingStatus {
        let response = try await api.send(.get, "/api/v1/billing/status", timeout: 8, retry: .connectFailures, background: background)
        return try response.decode(BillingStatus.self)
    }
}

public struct PeerSetupError: Error, Equatable, Sendable, UserVisibleError {
    public let status: Int
    public let serverMessage: String?
    /// Plan errors carry a machine `code` (entitlement/limits.go).
    public let code: String?

    public init(status: Int, serverMessage: String?, code: String? = nil) {
        self.status = status
        self.serverMessage = serverMessage
        self.code = code
    }

    public var userMessage: String {
        let lower = serverMessage?.lowercased() ?? ""
        let code = (self.code ?? "").lowercased()
        if code.contains("device_limit") || lower.contains("device limit") { return Messages.deviceLimit }
        if code == "subscription_required" || status == 402 || lower.contains("subscription") || lower.contains("premium") {
            return Messages.premiumRequired
        }
        // wg-manager also answers with log-style text ("create peer failed").
        // Only a written sentence is shown; everything else gets the fallback.
        let text = (serverMessage ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return ErrorText.isWrittenSentence(text) ? text : Messages.peerSetupFailed
    }
}
