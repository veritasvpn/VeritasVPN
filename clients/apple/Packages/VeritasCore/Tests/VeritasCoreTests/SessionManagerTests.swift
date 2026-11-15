import XCTest
@testable import VeritasCore

final class SessionManagerTests: XCTestCase {
    private let now = Date().timeIntervalSince1970

    /// Answers `/auth/refresh` with a rotated pair; other paths fall to `other`.
    private func rotating(counter: Counter, other: @escaping FakeTransport.Handler = { _ in (200, "{}") }) -> FakeTransport.Handler {
        let now = self.now
        return { request in
            guard request.url?.path == "/api/v1/auth/refresh" else { return try await other(request) }
            let n = counter.increment()
            // Slow enough that concurrent callers overlap.
            try await Task.sleep(nanoseconds: 50_000_000)
            return (200, Fixtures.json([
                "access_token": Fixtures.jwt(exp: now + 900, tag: "a\(n)"),
                "refresh_token": "refresh-\(n + 1)",
            ]))
        }
    }

    func testValidTokenIsReturnedWithoutRefresh() async throws {
        let token = Fixtures.jwt(exp: now + 900)
        let store = InMemoryCredentialStore(Fixtures.session(access: token))
        let transport = FakeTransport { _ in (500, "") }
        let session = SessionManager(store: store, api: Fixtures.client(transport))
        let value = try await session.validAccessToken(background: false)
        XCTAssertEqual(value, token)
        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testConcurrentCallersShareOneRefresh() async throws {
        let store = InMemoryCredentialStore(Fixtures.session(access: Fixtures.jwt(exp: now - 60)))
        let counter = Counter()
        let transport = FakeTransport(rotating(counter: counter))
        let session = SessionManager(store: store, api: Fixtures.client(transport))
        async let a = session.validAccessToken(background: false)
        async let b = session.validAccessToken(background: true)
        async let c = session.validAccessToken(background: false)
        let tokens = try await [a, b, c]
        XCTAssertEqual(Set(tokens).count, 1)
        XCTAssertEqual(transport.count(path: "/api/v1/auth/refresh"), 1)
        XCTAssertEqual(store.load()?.refreshToken, "refresh-2")
    }

    func testForegroundRejectedRefreshSignsOut() async {
        let store = InMemoryCredentialStore(Fixtures.session(access: Fixtures.jwt(exp: now - 60)))
        let transport = FakeTransport { _ in (401, #"{"error":"invalid refresh token"}"#) }
        let session = SessionManager(store: store, api: Fixtures.client(transport))
        let signedOut = Flag()
        await session.setSignedOutHandler { signedOut.set() }
        do {
            _ = try await session.validAccessToken(background: false)
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(error as? APIError, .sessionExpired)
        }
        XCTAssertNil(store.load())
        XCTAssertTrue(signedOut.value)
    }

    func testBackgroundRejectedRefreshKeepsTheSession() async {
        let store = InMemoryCredentialStore(Fixtures.session(access: Fixtures.jwt(exp: now - 60)))
        let transport = FakeTransport { _ in (401, #"{"error":"invalid refresh token"}"#) }
        let session = SessionManager(store: store, api: Fixtures.client(transport))
        do {
            _ = try await session.validAccessToken(background: true)
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(error as? APIError, .backgroundAuth)
        }
        XCTAssertNotNil(store.load())
        XCTAssertEqual(store.clearCount, 0)
    }

    func testProxy403AndOutageAreTransient() async {
        for (status, body) in [(403, "<html>Forbidden</html>"), (530, "error code: 1033"), (502, "")] {
            let store = InMemoryCredentialStore(Fixtures.session(access: Fixtures.jwt(exp: now - 60)))
            let transport = FakeTransport { _ in (status, body) }
            let session = SessionManager(store: store, api: Fixtures.client(transport))
            let result = await session.refresh(force: true, rejectedToken: nil)
            XCTAssertEqual(result, .unavailable, "status \(status)")
            XCTAssertNotNil(store.load(), "status \(status)")
        }
    }

    func testRefreshIsSkippedWhenAnotherCallerAlreadyRotated() async {
        let fresh = Fixtures.jwt(exp: now + 900, tag: "new")
        let store = InMemoryCredentialStore(Fixtures.session(access: fresh))
        let transport = FakeTransport { _ in (500, "") }
        let session = SessionManager(store: store, api: Fixtures.client(transport))
        let result = await session.refresh(force: true, rejectedToken: Fixtures.jwt(exp: now + 900, tag: "old"))
        XCTAssertEqual(result, .refreshed)
        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testAuthorizedRetriesOnceAfter401WithRotatedToken() async throws {
        let stale = Fixtures.jwt(exp: now + 900, tag: "stale")
        let store = InMemoryCredentialStore(Fixtures.session(access: stale))
        let counter = Counter()
        let transport = FakeTransport(rotating(counter: counter) { request in
            let auth = request.value(forHTTPHeaderField: "Authorization") ?? ""
            return auth == "Bearer \(stale)" ? (401, "{}") : (200, #"{"is_premium":true}"#)
        })
        let client = Fixtures.client(transport)
        let session = SessionManager(store: store, api: client)
        let api = AuthorizedAPI(api: client, session: session)
        let response = try await api.send(.get, "/api/v1/billing/status", background: true)
        XCTAssertEqual(response.status, 200)
        XCTAssertEqual(transport.count(path: "/api/v1/auth/refresh"), 1)
        XCTAssertEqual(transport.count(path: "/api/v1/billing/status"), 2)
    }

    func testBackground401AfterSuccessfulRefreshDoesNotSignOut() async throws {
        let store = InMemoryCredentialStore(Fixtures.session(access: Fixtures.jwt(exp: now + 900)))
        let counter = Counter()
        let transport = FakeTransport(rotating(counter: counter) { _ in (401, #"{"error":"nope"}"#) })
        let client = Fixtures.client(transport)
        let api = AuthorizedAPI(api: client, session: SessionManager(store: store, api: client))
        let response = try await api.send(.get, "/api/v1/wg/peers", background: true)
        XCTAssertEqual(response.status, 401)
        XCTAssertNotNil(store.load())
        XCTAssertEqual(store.clearCount, 0)
    }

    func testBackgroundCleanupWithDeadSessionNeverSignsOut() async {
        let store = InMemoryCredentialStore(Fixtures.session(access: Fixtures.jwt(exp: now + 900)))
        let transport = FakeTransport { _ in (401, "{}") }
        let client = Fixtures.client(transport)
        let peers = PeerService(api: AuthorizedAPI(api: client, session: SessionManager(store: store, api: client)))
        await peers.deletePeer(id: "p1")
        XCTAssertNotNil(store.load())
    }

    func testJWTExpiry() {
        XCTAssertFalse(JWT.isExpired(Fixtures.jwt(exp: now + 900)))
        XCTAssertTrue(JWT.isExpired(Fixtures.jwt(exp: now + 10)))
        XCTAssertTrue(JWT.isExpired("not-a-token"))
        XCTAssertTrue(JWT.isExpired(nil))
    }

    func testRefreshStatusRule() {
        XCTAssertEqual(RefreshStatus(statusCode: 401), .rejected)
        XCTAssertEqual(RefreshStatus(statusCode: 403), .transient)
        XCTAssertEqual(RefreshStatus(statusCode: 200), .success)
        XCTAssertEqual(RefreshStatus(statusCode: 530), .transient)
    }
}

final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    var value: Int { lock.lock(); defer { lock.unlock() }; return count }
    @discardableResult func increment() -> Int { lock.lock(); defer { lock.unlock() }; count += 1; return count }
}

final class Flag: @unchecked Sendable {
    private let lock = NSLock()
    private var raised = false
    var value: Bool { lock.lock(); defer { lock.unlock() }; return raised }
    func set() { lock.lock(); raised = true; lock.unlock() }
}

