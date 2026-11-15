import Foundation

public enum AuthFailure: Error, Equatable, Sendable, UserVisibleError {
    /// Email account exists but is not verified yet. Show the verify screen.
    case verificationRequired(email: String)
    case accountAlreadyExists(email: String)
    /// The server wants a Turnstile token for this attempt.
    case turnstileRequired
    /// A descriptive, display-safe sentence.
    case message(String)

    public var userMessage: String {
        switch self {
        case .verificationRequired: return Messages.verifyEmailFirst
        case .accountAlreadyExists: return Messages.accountExists
        case .turnstileRequired: return Messages.securityCheckRequired
        case .message(let text): return text
        }
    }
}

/// Sign-in, sign-up and account calls. Same endpoints and rules as
/// android/.../auth/AuthRepository.kt.
public struct AuthService: Sendable {
    public let api: APIClient
    public let session: SessionManager

    public init(api: APIClient, session: SessionManager) {
        self.api = api
        self.session = session
    }

    public static func normalizeEmail(_ email: String) -> String {
        email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    /// Port of `extractError`: server text to a fixed, friendly sentence.
    /// Unknown text is shown only when display-safe.
    public static func friendlyAuthMessage(status: Int, serverMessage: String?) -> String {
        if ErrorText.isServerUnavailableStatus(status) { return Messages.serverUnreachable }
        let raw = serverMessage?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let msg = raw.isEmpty ? defaultAuthError(status) : raw
        let lower = msg.lowercased()
        if lower.contains("email_not_verified") || lower.contains("verify your email") { return Messages.verifyEmailFirst }
        if lower.contains("security check required") || lower.contains("security check failed")
            || (lower.contains("verification required") && !lower.contains("email")) {
            return Messages.securityCheckRequired
        }
        if lower.contains("incorrect email or password") || lower.contains("invalid email or password") {
            return Messages.incorrectCredentials
        }
        if lower.contains("password must be at least") { return Messages.passwordTooShort }
        if lower.contains("already exists") { return Messages.accountExists }
        if lower.contains("too many") { return Messages.tooManyAttempts }
        if lower.contains("account") && (lower.contains("not found") || lower.contains("invalid") || lower.contains("unknown")) {
            return Messages.accountIdNotFound
        }
        return ErrorText.isWrittenSentence(msg) ? msg : Messages.signInServiceUnreachable
    }

    static func defaultAuthError(_ status: Int) -> String {
        switch status {
        case 401: return Messages.incorrectCredentials
        case 403: return Messages.verifyEmailFirst
        case 429: return Messages.tooManyAttempts
        default: return ""
        }
    }

    private func failure(from response: APIResponse) -> AuthFailure {
        if ErrorText.isServerUnavailableStatus(response.status) || ErrorText.looksLikeEdgeResponse(response.bodyText) {
            return .message(Messages.serverUnreachable)
        }
        return .message(Self.friendlyAuthMessage(status: response.status, serverMessage: response.serverMessage))
    }

    private func decodeAuth(_ response: APIResponse) throws -> AuthResponse {
        do {
            return try response.decode(AuthResponse.self)
        } catch let error as APIError {
            throw AuthFailure.message(error.userMessage)
        }
    }

    private func send(_ path: String, _ body: JSONValue, timeout: TimeInterval, retry: RetryPolicy) async throws -> APIResponse {
        do {
            return try await api.send(.post, path, body: body, timeout: timeout, retry: retry)
        } catch let error as APIError {
            throw AuthFailure.message(error.userMessage)
        }
    }

    @discardableResult
    public func signIn(email: String, password: String, turnstileToken: String = "") async throws -> StoredSession {
        let normalized = Self.normalizeEmail(email)
        var body: [String: JSONValue] = ["email": .string(normalized), "password": .string(password)]
        if !turnstileToken.isEmpty { body["turnstile_token"] = .string(turnstileToken) }
        let response = try await send("/api/v1/auth/signin", .object(body), timeout: 20,
                                      retry: turnstileToken.isEmpty ? .connectAndTimeouts : .connectFailures)
        if !response.isSuccess {
            let message = response.serverMessage?.lowercased() ?? ""
            if message.contains("verify your email") || message.contains("email_not_verified") {
                throw AuthFailure.verificationRequired(email: normalized)
            }
            if message.contains("security check required") { throw AuthFailure.turnstileRequired }
            throw failure(from: response)
        }
        let auth = try decodeAuth(response)
        let trimmed = auth.email?.trimmingCharacters(in: .whitespacesAndNewlines)
        let stored = StoredSession(
            accountId: auth.accountId,
            email: (trimmed?.isEmpty == false) ? trimmed : normalized,
            isAnonymous: false,
            accessToken: auth.accessToken,
            refreshToken: auth.refreshToken
        )
        await session.save(stored)
        return stored
    }

    /// Email sign-up. One bounded attempt so a failed sign-up never looks
    /// like a frozen button. Returns nil when the email must be verified first.
    public func signUp(email: String, password: String, turnstileToken: String) async throws -> StoredSession? {
        let normalized = Self.normalizeEmail(email)
        let response = try await send("/api/v1/auth/register", [
            "email": .string(normalized),
            "password": .string(password),
            "turnstile_token": .string(turnstileToken),
        ], timeout: 10, retry: .none)
        if !response.isSuccess {
            if response.serverMessage == Messages.accountExists { throw AuthFailure.accountAlreadyExists(email: normalized) }
            throw failure(from: response)
        }
        let auth = try decodeAuth(response)
        if auth.verificationRequired || auth.accessToken.isEmpty {
            throw AuthFailure.verificationRequired(email: normalized)
        }
        let stored = StoredSession(accountId: auth.accountId, email: normalized, isAnonymous: false,
                                   accessToken: auth.accessToken, refreshToken: auth.refreshToken)
        await session.save(stored)
        return stored
    }

    @discardableResult
    public func signIn(accountId: String, turnstileToken: String = "") async throws -> StoredSession {
        var body: [String: JSONValue] = ["account_id": .string(accountId.trimmingCharacters(in: .whitespacesAndNewlines))]
        if !turnstileToken.isEmpty { body["turnstile_token"] = .string(turnstileToken) }
        let response = try await send("/api/v1/auth/signin-account", .object(body), timeout: 20,
                                      retry: turnstileToken.isEmpty ? .connectAndTimeouts : .connectFailures)
        if !response.isSuccess {
            if (response.serverMessage?.lowercased() ?? "").contains("security check required") {
                throw AuthFailure.turnstileRequired
            }
            throw failure(from: response)
        }
        let auth = try decodeAuth(response)
        let stored = StoredSession(accountId: auth.accountId, email: nil, isAnonymous: true,
                                   accessToken: auth.accessToken, refreshToken: auth.refreshToken)
        await session.save(stored)
        return stored
    }

    /// Creates an anonymous account. The caller must show the Account ID.
    @discardableResult
    public func registerAnonymous(turnstileToken: String) async throws -> StoredSession {
        let response = try await send("/api/v1/auth/register-anonymous",
                                      ["turnstile_token": .string(turnstileToken)], timeout: 10, retry: .none)
        if !response.isSuccess { throw failure(from: response) }
        let auth = try decodeAuth(response)
        let stored = StoredSession(accountId: auth.accountId, email: nil, isAnonymous: true,
                                   accessToken: auth.accessToken, refreshToken: auth.refreshToken)
        await session.save(stored)
        return stored
    }

    public func resendVerification(email: String) async throws {
        let response = try await send("/api/v1/auth/resend-verification",
                                      ["email": .string(Self.normalizeEmail(email))], timeout: 20, retry: .connectAndTimeouts)
        if !response.isSuccess { throw failure(from: response) }
    }

    public func resetPassword(email: String) async throws {
        let response = try await send("/api/v1/auth/reset-password",
                                      ["email": .string(Self.normalizeEmail(email))], timeout: 20, retry: .connectAndTimeouts)
        if !response.isSuccess { throw failure(from: response) }
    }

    /// Fills a missing email from GET /auth/me. Never signs out.
    public func refreshStoredEmail() async -> StoredSession? {
        guard let existing = await session.currentSession() else { return nil }
        if let email = existing.email, !email.isEmpty { return existing }
        if existing.isAnonymous { return existing }
        let authorized = AuthorizedAPI(api: api, session: session)
        guard let response = try? await authorized.send(.get, "/api/v1/auth/me", background: true),
              let profile = try? response.decode(AccountProfile.self),
              let email = profile.email?.trimmingCharacters(in: .whitespacesAndNewlines), !email.isEmpty else {
            return existing
        }
        await session.updateEmail(email)
        return await session.currentSession()
    }

    /// Ends every session on the server, then locally.
    public func logoutAllSessions() async throws {
        let authorized = AuthorizedAPI(api: api, session: session)
        let response = try await authorized.send(.post, "/api/v1/auth/logout-all", body: .object([:]))
        if !response.isSuccess { throw failure(from: response) }
        await session.signOut()
    }

    /// Permanently deletes the account. Email accounts send the password;
    /// anonymous accounts send a Turnstile token when the server asks.
    public func deleteAccount(password: String, turnstileToken: String) async throws {
        var body: [String: JSONValue] = [:]
        if !password.isEmpty { body["password"] = .string(password) }
        if !turnstileToken.isEmpty { body["turnstile_token"] = .string(turnstileToken) }
        let authorized = AuthorizedAPI(api: api, session: session)
        let response = try await authorized.send(.delete, "/api/v1/auth/account", body: .object(body))
        if response.status != 204 && !response.isSuccess {
            if (response.serverMessage?.lowercased() ?? "").contains("security check required") {
                throw AuthFailure.turnstileRequired
            }
            throw failure(from: response)
        }
        await session.signOut()
    }
}

/// Client-side password rules, same as the Android sign-up form.
public enum PasswordRules {
    public static func problem(password: String, confirmation: String) -> String? {
        if password.isEmpty { return Messages.enterPassword }
        if password.count < 10 { return Messages.passwordTooShort }
        if !password.contains(where: { $0.isUppercase }) { return Messages.passwordNeedsUppercase }
        if !password.contains(where: { $0.isLowercase }) { return Messages.passwordNeedsLowercase }
        if !password.contains(where: { $0.isNumber }) { return Messages.passwordNeedsNumber }
        if confirmation.isEmpty { return Messages.confirmPassword }
        if confirmation != password { return Messages.passwordsDontMatch }
        return nil
    }

    /// 0...4, one point per rule met (length, upper, lower, digit).
    public static func strength(_ password: String) -> Int {
        var score = 0
        if password.count >= 10 { score += 1 }
        if password.contains(where: { $0.isUppercase }) { score += 1 }
        if password.contains(where: { $0.isLowercase }) { score += 1 }
        if password.contains(where: { $0.isNumber }) { score += 1 }
        return score
    }
}
