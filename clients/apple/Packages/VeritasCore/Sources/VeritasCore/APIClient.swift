import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum HTTPMethod: String, Sendable {
    case get = "GET"
    case post = "POST"
    case patch = "PATCH"
    case delete = "DELETE"
}

/// One HTTP exchange. Tests replace it with a scripted fake.
public protocol HTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

/// Production transport: an ephemeral session with no cookies and no cache,
/// so nothing about the user's traffic or tokens lands on disk.
public struct URLSessionTransport: HTTPTransport {
    private let session: URLSession

    public init(session: URLSession = URLSessionTransport.makeSession()) {
        self.session = session
    }

    public static func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 20
        config.waitsForConnectivity = false
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config)
    }

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
    }
}

/// A failed API call, already reduced to something a person can read.
public enum APIError: Error, Equatable, Sendable, UserVisibleError {
    /// No HTTP answer: offline, DNS, refused, TLS, timeout.
    case transport(URLError.Code)
    /// The service is down or a proxy answered (5xx, 408, Cloudflare 1033/530 pages, HTML, bad JSON).
    case serverUnavailable(status: Int)
    /// A real API answer that is not 2xx. `serverMessage` is the JSON
    /// `error`/`message` text, or nil. It is never shown unless display-safe.
    case http(status: Int, serverMessage: String?)
    /// The session is over and the user has to sign in again (foreground calls only).
    case sessionExpired
    /// A background call could not confirm the session. The user stays signed in.
    case backgroundAuth

    public var userMessage: String {
        switch self {
        case .transport(let code):
            return ErrorText.message(for: URLError(code))
        case .serverUnavailable:
            return Messages.serverUnreachable
        case .http(_, let message):
            return ErrorText.safe(message)
        case .sessionExpired:
            return Messages.sessionExpired
        case .backgroundAuth:
            return Messages.signInAgain
        }
    }

    public var status: Int? {
        switch self {
        case .serverUnavailable(let status), .http(let status, _): return status
        default: return nil
        }
    }
}

public struct APIResponse: Sendable, Equatable {
    public let status: Int
    public let data: Data

    public init(status: Int, data: Data) {
        self.status = status
        self.data = data
    }

    public var isSuccess: Bool { (200...299).contains(status) }

    public var bodyText: String { String(decoding: data, as: UTF8.self) }

    /// The server's `error` (or `message`) text, if the body is API JSON.
    public var serverMessage: String? {
        guard let body = try? JSONDecoder().decode(ServerErrorBody.self, from: data) else { return nil }
        let text = (body.error ?? body.message)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (text?.isEmpty ?? true) ? nil : text
    }

    /// The server's machine `code`, when it sends one (plan errors).
    public var serverCode: String? {
        guard let body = try? JSONDecoder().decode(ServerErrorBody.self, from: data) else { return nil }
        return body.code
    }

    /// The API-level failure for a non-2xx answer. Edge pages and 5xx map to
    /// `.serverUnavailable` so a Cloudflare 1033 never reaches the screen.
    public var failure: APIError {
        if ErrorText.isServerUnavailableStatus(status) || ErrorText.looksLikeEdgeResponse(bodyText) {
            return .serverUnavailable(status: status)
        }
        return .http(status: status, serverMessage: serverMessage)
    }

    /// Throws `failure` unless 2xx.
    public func ensureSuccess() throws {
        if !isSuccess { throw failure }
    }

    /// Decodes a 2xx JSON body. Anything else, including an HTML page with a
    /// 200 from a captive portal, is reported as the service being unreachable.
    public func decode<T: Decodable>(_ type: T.Type) throws -> T {
        try ensureSuccess()
        if ErrorText.looksLikeEdgeResponse(bodyText) { throw APIError.serverUnavailable(status: status) }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw APIError.serverUnavailable(status: status)
        }
    }
}

/// Retries for idempotent-enough calls, same budget as Android's ApiClient:
/// up to two retries after 1 s and 2 s for connection failures, and for
/// timeouts only when asked.
public struct RetryPolicy: Sendable, Equatable {
    public var maxRetries: Int
    public var retryOnTimeout: Bool

    public static let none = RetryPolicy(maxRetries: 0, retryOnTimeout: false)
    public static let connectFailures = RetryPolicy(maxRetries: 2, retryOnTimeout: false)
    public static let connectAndTimeouts = RetryPolicy(maxRetries: 2, retryOnTimeout: true)

    public init(maxRetries: Int, retryOnTimeout: Bool) {
        self.maxRetries = maxRetries
        self.retryOnTimeout = retryOnTimeout
    }

    func shouldRetry(_ code: URLError.Code) -> Bool {
        switch code {
        case .cannotConnectToHost, .cannotFindHost, .dnsLookupFailed, .networkConnectionLost:
            return true
        case .timedOut:
            return retryOnTimeout
        default:
            return false
        }
    }

    static func backoffNanoseconds(attempt: Int) -> UInt64 {
        attempt == 0 ? 1_000_000_000 : 2_000_000_000
    }
}

public struct APIClient: Sendable {
    public static let productionBaseURL = URL(string: "https://api.veritasvpn.cloud")!

    public let baseURL: URL
    public let clientName: String
    private let transport: HTTPTransport
    private let sleep: @Sendable (UInt64) async throws -> Void

    /// `clientName` is sent as `X-Veritas-Client` (telemetry only on the
    /// server): "ios" or "macos".
    public init(
        baseURL: URL = APIClient.productionBaseURL,
        clientName: String,
        transport: HTTPTransport = URLSessionTransport(),
        sleep: @escaping @Sendable (UInt64) async throws -> Void = { try await Task.sleep(nanoseconds: $0) }
    ) {
        self.baseURL = baseURL
        self.clientName = clientName
        self.transport = transport
        self.sleep = sleep
    }

    public func makeRequest(
        _ method: HTTPMethod,
        _ path: String,
        body: JSONValue? = nil,
        token: String? = nil,
        timeout: TimeInterval = 20
    ) throws -> URLRequest {
        guard let url = URL(string: path, relativeTo: baseURL)?.absoluteURL else {
            throw APIError.http(status: 0, serverMessage: nil)
        }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        request.httpMethod = method.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(clientName, forHTTPHeaderField: "X-Veritas-Client")
        if let body {
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.httpBody = try body.encoded()
        }
        if let token, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        return request
    }

    /// Sends one request. Transport failures become `APIError.transport`.
    /// Any HTTP status is returned as an `APIResponse`; callers decide.
    public func send(
        _ method: HTTPMethod,
        _ path: String,
        body: JSONValue? = nil,
        token: String? = nil,
        timeout: TimeInterval = 20,
        retry: RetryPolicy = .none
    ) async throws -> APIResponse {
        let request = try makeRequest(method, path, body: body, token: token, timeout: timeout)
        var attempt = 0
        while true {
            do {
                let (data, http) = try await transport.send(request)
                return APIResponse(status: http.statusCode, data: data)
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as URLError {
                if error.code == .cancelled { throw CancellationError() }
                if attempt < retry.maxRetries, retry.shouldRetry(error.code) {
                    try await sleep(RetryPolicy.backoffNanoseconds(attempt: attempt))
                    attempt += 1
                    continue
                }
                throw APIError.transport(error.code)
            } catch let error as APIError {
                throw error
            } catch {
                let ns = error as NSError
                if ns.domain == NSURLErrorDomain {
                    throw APIError.transport(URLError.Code(rawValue: ns.code))
                }
                throw APIError.transport(.unknown)
            }
        }
    }
}
