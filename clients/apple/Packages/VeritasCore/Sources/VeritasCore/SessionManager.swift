import Foundation

/// Result of one `/auth/refresh` exchange.
public enum RefreshResult: Equatable, Sendable {
    case refreshed
    /// The server rejected the refresh token (401). The session is over.
    case rejected
    /// Network trouble, 5xx, 403 from a proxy, or a bad body. Try later.
    case unavailable
}

/// Owns the stored session and the single-flight token refresh.
///
/// Refresh tokens are single-use on the server, so every caller in the
/// process shares one in-flight exchange. A caller that arrives after another
/// one already rotated the token reuses the new token instead of spending the
/// refresh token again (same rule as Android's `singleFlightRefresh` and the
/// desktop `refreshSessionOutcome`).
///
/// Only a foreground call whose refresh is rejected clears the session.
/// Background calls (polls, cleanup) never sign the user out.
public actor SessionManager {
    private let store: CredentialStore
    private let api: APIClient
    private let now: @Sendable () -> Date
    private var inFlight: Task<RefreshResult, Never>?
    private var onSignedOut: (@Sendable () -> Void)?

    /// Number of `/auth/refresh` calls actually sent (tests).
    public private(set) var exchangeCount = 0

    public init(store: CredentialStore, api: APIClient, now: @escaping @Sendable () -> Date = { Date() }) {
        self.store = store
        self.api = api
        self.now = now
    }

    /// Called once when a foreground failure ends the session.
    public func setSignedOutHandler(_ handler: (@Sendable () -> Void)?) {
        onSignedOut = handler
    }

    public func currentSession() -> StoredSession? { store.load() }

    public func save(_ session: StoredSession) { store.save(session) }

    public func updateEmail(_ email: String) {
        guard var session = store.load() else { return }
        session.email = email
        store.save(session)
    }

    /// Local sign-out. Does not call the server.
    public func signOut() {
        inFlight?.cancel()
        inFlight = nil
        store.clear()
    }

    private func expireSession() {
        store.clear()
        onSignedOut?()
    }

    /// A token that is not about to expire, refreshing when needed.
    public func validAccessToken(background: Bool) async throws -> String {
        guard let session = store.load() else {
            throw background ? APIError.backgroundAuth : APIError.sessionExpired
        }
        if !JWT.isExpired(session.accessToken, now: now()) { return session.accessToken }
        switch await refresh(force: false, rejectedToken: nil) {
        case .refreshed:
            if let token = store.load()?.accessToken, !token.isEmpty { return token }
            throw background ? APIError.backgroundAuth : APIError.sessionExpired
        case .rejected:
            if background { throw APIError.backgroundAuth }
            expireSession()
            throw APIError.sessionExpired
        case .unavailable:
            throw APIError.serverUnavailable(status: 0)
        }
    }

    /// Single-flight refresh. `rejectedToken` is the access token a server
    /// just answered 401 for; if the stored token already changed, the
    /// refresh is skipped and the newer token is reused.
    public func refresh(force: Bool, rejectedToken: String?) async -> RefreshResult {
        if let running = inFlight {
            return await running.value
        }
        guard let session = store.load() else { return .rejected }
        if let rejectedToken, session.accessToken != rejectedToken, !JWT.isExpired(session.accessToken, now: now()) {
            return .refreshed
        }
        if !force, !JWT.isExpired(session.accessToken, now: now()) {
            return .refreshed
        }
        guard !session.refreshToken.isEmpty else { return .rejected }
        let refreshToken = session.refreshToken
        let task = Task { await self.exchange(refreshToken: refreshToken) }
        inFlight = task
        let result = await task.value
        inFlight = nil
        return result
    }

    private func exchange(refreshToken: String) async -> RefreshResult {
        exchangeCount += 1
        let response: APIResponse
        do {
            response = try await api.send(.post, "/api/v1/auth/refresh", body: ["refresh_token": .string(refreshToken)], retry: .connectFailures)
        } catch {
            return .unavailable
        }
        switch RefreshStatus(statusCode: response.status) {
        case .rejected:
            return .rejected
        case .transient:
            return .unavailable
        case .success:
            guard !ErrorText.looksLikeEdgeResponse(response.bodyText),
                  let auth = try? JSONDecoder().decode(AuthResponse.self, from: response.data),
                  !auth.accessToken.isEmpty, !auth.refreshToken.isEmpty,
                  var session = store.load() else {
                return .unavailable
            }
            // Ignore a late answer after sign-out or an account switch.
            guard session.refreshToken == refreshToken else { return .unavailable }
            session.accessToken = auth.accessToken
            session.refreshToken = auth.refreshToken
            store.save(session)
            return .refreshed
        }
    }
}

/// Only a 401 from `/auth/refresh` ends the session. A 403, including one
/// from a proxy in front of the API, is transient.
public enum RefreshStatus: Equatable, Sendable {
    case success, rejected, transient

    public init(statusCode: Int) {
        switch statusCode {
        case 401: self = .rejected
        case 200...299: self = .success
        default: self = .transient
        }
    }
}

/// Bearer-authenticated API calls with one refresh-and-retry on 401.
public struct AuthorizedAPI: Sendable {
    public let api: APIClient
    public let session: SessionManager

    public init(api: APIClient, session: SessionManager) {
        self.api = api
        self.session = session
    }

    /// `background: true` for polls and cleanup: those report
    /// `APIError.backgroundAuth` instead of ever clearing the session.
    /// A second 401 after a successful refresh is returned to the caller
    /// (server misconfiguration) and does not sign out.
    public func send(
        _ method: HTTPMethod,
        _ path: String,
        body: JSONValue? = nil,
        timeout: TimeInterval = 20,
        retry: RetryPolicy = .none,
        background: Bool = false
    ) async throws -> APIResponse {
        let token = try await session.validAccessToken(background: background)
        let first = try await api.send(method, path, body: body, token: token, timeout: timeout, retry: retry)
        guard first.status == 401 else { return first }
        switch await session.refresh(force: true, rejectedToken: token) {
        case .refreshed:
            guard let fresh = await session.currentSession()?.accessToken, !fresh.isEmpty else {
                throw background ? APIError.backgroundAuth : APIError.sessionExpired
            }
            return try await api.send(method, path, body: body, token: fresh, timeout: timeout, retry: retry)
        case .rejected:
            if background { throw APIError.backgroundAuth }
            await session.signOutAfterRejection()
            throw APIError.sessionExpired
        case .unavailable:
            throw APIError.serverUnavailable(status: 0)
        }
    }
}

extension SessionManager {
    func signOutAfterRejection() {
        expireSession()
    }
}
