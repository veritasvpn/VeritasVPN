import Foundation
@testable import VeritasCore

/// Scripted HTTP fake. Each handler gets the request and returns a status
/// and body, or throws a URLError.
final class FakeTransport: HTTPTransport, @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) async throws -> (Int, String)

    private let lock = NSLock()
    private var handler: Handler
    private(set) var requests: [URLRequest] = []

    init(_ handler: @escaping Handler) { self.handler = handler }

    func setHandler(_ handler: @escaping Handler) {
        lock.lock(); self.handler = handler; lock.unlock()
    }

    func paths() -> [String] {
        lock.lock(); defer { lock.unlock() }
        return requests.compactMap { $0.url?.path }
    }

    func count(path: String) -> Int { paths().filter { $0 == path }.count }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        lock.lock()
        requests.append(request)
        let handler = self.handler
        lock.unlock()
        let (status, body) = try await handler(request)
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
        return (Data(body.utf8), response)
    }
}

enum Fixtures {
    /// An unsigned test JWT; only `exp` matters to the client. Built at
    /// runtime so no token-shaped literal sits in the repository.
    static func jwt(exp: TimeInterval, tag: String = "") -> String {
        func enc(_ object: [String: Any]) -> String {
            let data = try! JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
            return data.base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        return enc(["alg": "none"]) + "." + enc(["exp": exp, "tag": tag]) + ".sig"
    }

    /// A syntactically valid WireGuard key (32 repeated bytes).
    static func key(_ byte: UInt8) -> String {
        Data(repeating: byte, count: 32).base64EncodedString()
    }

    static func json(_ object: [String: Any]) -> String {
        String(decoding: try! JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), as: UTF8.self)
    }

    static func client(_ transport: FakeTransport) -> APIClient {
        APIClient(baseURL: URL(string: "https://api.example.test")!, clientName: "ios", transport: transport, sleep: { _ in })
    }

    static func session(access: String, refresh: String = "refresh-1") -> StoredSession {
        StoredSession(accountId: "acct", email: "person@example.test", isAnonymous: false, accessToken: access, refreshToken: refresh)
    }

    static func body(of request: URLRequest) -> [String: Any] {
        guard let data = request.httpBody,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return object
    }
}
