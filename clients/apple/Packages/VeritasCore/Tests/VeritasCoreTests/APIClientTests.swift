import XCTest
@testable import VeritasCore

final class APIClientTests: XCTestCase {
    func testRequestShapeHeadersAndBody() async throws {
        let transport = FakeTransport { _ in (200, "{}") }
        let client = Fixtures.client(transport)
        _ = try await client.send(.post, "/api/v1/auth/signin", body: ["email": "a@b.test", "password": "pw"], token: "tok")
        let request = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(request.url?.absoluteString, "https://api.example.test/api/v1/auth/signin")
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Veritas-Client"), "ios")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer tok")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json; charset=utf-8")
        XCTAssertEqual(Fixtures.body(of: request)["email"] as? String, "a@b.test")
    }

    func testRetriesConnectFailuresTwiceThenThrowsTransport() async {
        let transport = FakeTransport { _ in throw URLError(.cannotConnectToHost) }
        let client = Fixtures.client(transport)
        do {
            _ = try await client.send(.get, "/x", retry: .connectFailures)
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(error as? APIError, .transport(.cannotConnectToHost))
            XCTAssertEqual(ErrorText.message(for: error), Messages.serverUnreachable)
        }
        XCTAssertEqual(transport.requests.count, 3)
    }

    func testTimeoutRetriedOnlyWhenAsked() async {
        let transport = FakeTransport { _ in throw URLError(.timedOut) }
        let client = Fixtures.client(transport)
        _ = try? await client.send(.get, "/x", retry: .connectFailures)
        XCTAssertEqual(transport.requests.count, 1)
        _ = try? await client.send(.get, "/x", retry: .connectAndTimeouts)
        XCTAssertEqual(transport.requests.count, 4)
    }

    func testOfflineIsNotRetried() async {
        let transport = FakeTransport { _ in throw URLError(.notConnectedToInternet) }
        let client = Fixtures.client(transport)
        do {
            _ = try await client.send(.get, "/x", retry: .connectAndTimeouts)
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(ErrorText.message(for: error), Messages.offline)
        }
        XCTAssertEqual(transport.requests.count, 1)
    }

    func testDecodesPeerResponseWithPresharedKeyAndLanEndpoint() throws {
        let body = Fixtures.json([
            "peer_id": "p1",
            "server_public_key": Fixtures.key(2),
            "server_endpoint": "192.0.2.10:51820",
            "server_endpoint_lan": "192.0.2.10:51820",
            "server_endpoint_wan": "vpn.example.test:443",
            "assigned_ip": "10.0.0.7/32",
            "dns_server": "10.0.0.1",
            "preshared_key": Fixtures.key(3),
            "client_allowed_ips": ["0.0.0.0/0", "::/0"],
            "stealth_available": true,
            "stealth_path_prefix": "abc123",
            "unknown_future_field": 42,
        ])
        let peer = try APIResponse(status: 200, data: Data(body.utf8)).decode(PeerResponse.self)
        XCTAssertEqual(peer.peerId, "p1")
        XCTAssertEqual(peer.presharedKey, Fixtures.key(3))
        XCTAssertEqual(peer.serverEndpointLan, "192.0.2.10:51820")
        XCTAssertEqual(peer.serverEndpointWan, "vpn.example.test:443")
        XCTAssertTrue(peer.stealthAvailable)
        XCTAssertEqual(peer.clientAllowedIps ?? [], ["0.0.0.0/0", "::/0"])
    }

    func testPeerResponseMissingKeyIsServerUnavailableNotACrash() {
        let response = APIResponse(status: 200, data: Data(#"{"peer_id":"p1"}"#.utf8))
        XCTAssertThrowsError(try response.decode(PeerResponse.self)) { error in
            XCTAssertEqual(ErrorText.message(for: error), Messages.serverUnreachable)
        }
    }

    func testBillingStatusDefaults() throws {
        let status = try APIResponse(status: 200, data: Data(#"{"is_premium":true,"trial_eligible":true,"payments":[{"amount_cents":300,"is_trial":false}]}"#.utf8))
            .decode(BillingStatus.self)
        XCTAssertTrue(status.isPremium)
        XCTAssertTrue(status.trialEligible)
        XCTAssertEqual(status.tier, "free")
        XCTAssertEqual(status.payments?.first?.amountCents, 300)
        XCTAssertFalse(status.isPaused)
    }

    func testServerMessageAndCodeExtraction() {
        let response = APIResponse(status: 403, data: Data(#"{"error":"an active paid subscription is required","code":"subscription_required"}"#.utf8))
        XCTAssertEqual(response.serverMessage, "an active paid subscription is required")
        XCTAssertEqual(response.serverCode, "subscription_required")
        XCTAssertEqual(response.failure, .http(status: 403, serverMessage: "an active paid subscription is required"))
    }

    func testPeerServiceCreateSendsShieldMetadataAndDeviceId() async throws {
        let future = Date().timeIntervalSince1970 + 3600
        let store = InMemoryCredentialStore(Fixtures.session(access: Fixtures.jwt(exp: future)))
        let transport = FakeTransport { request in
            XCTAssertEqual(request.url?.path, "/api/v1/wg/peers")
            return (200, Fixtures.json([
                "peer_id": "p9", "server_public_key": Fixtures.key(1), "server_endpoint": "vpn.example.test:443",
                "assigned_ip": "10.0.0.9", "dns_server": "10.0.0.1",
            ]))
        }
        let client = Fixtures.client(transport)
        let peers = PeerService(api: AuthorizedAPI(api: client, session: SessionManager(store: store, api: client)))
        let peer = try await peers.createPeer(
            publicKey: Fixtures.key(5), deviceId: "device-1",
            metadata: DeviceMetadata(platform: "iOS", model: "Apple iPhone", osVersion: "iOS 18.0", clientVersion: "1.0"),
            shield: ShieldPolicy())
        XCTAssertEqual(peer.peerId, "p9")
        let body = Fixtures.body(of: try XCTUnwrap(transport.requests.first))
        XCTAssertEqual(body["public_key"] as? String, Fixtures.key(5))
        XCTAssertEqual(body["device_id"] as? String, "device-1")
        XCTAssertEqual(body["device_platform"] as? String, "iOS")
        let shield = try XCTUnwrap(body["shield"] as? [String: Any])
        XCTAssertEqual(shield["block_malicious"] as? Bool, true)
        XCTAssertEqual(shield["block_trackers"] as? Bool, true)
        XCTAssertEqual(shield["block_ads"] as? Bool, false)
    }

    func testPeerServiceCreateMapsPlanErrors() async {
        let future = Date().timeIntervalSince1970 + 3600
        let store = InMemoryCredentialStore(Fixtures.session(access: Fixtures.jwt(exp: future)))
        let transport = FakeTransport { _ in (403, #"{"error":"an active paid subscription is required","code":"subscription_required"}"#) }
        let client = Fixtures.client(transport)
        let peers = PeerService(api: AuthorizedAPI(api: client, session: SessionManager(store: store, api: client)))
        do {
            _ = try await peers.createPeer(publicKey: Fixtures.key(5), deviceId: "d",
                                           metadata: DeviceMetadata(platform: "iOS", model: "", osVersion: "", clientVersion: ""),
                                           shield: ShieldPolicy())
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(ErrorText.message(for: error), Messages.premiumRequired)
        }
    }

    func testPeerServiceCreateOn1033IsUnreachable() async {
        let future = Date().timeIntervalSince1970 + 3600
        let store = InMemoryCredentialStore(Fixtures.session(access: Fixtures.jwt(exp: future)))
        let transport = FakeTransport { _ in (530, "error code: 1033") }
        let client = Fixtures.client(transport)
        let peers = PeerService(api: AuthorizedAPI(api: client, session: SessionManager(store: store, api: client)))
        do {
            _ = try await peers.createPeer(publicKey: Fixtures.key(5), deviceId: "d",
                                           metadata: DeviceMetadata(platform: "iOS", model: "", osVersion: "", clientVersion: ""),
                                           shield: ShieldPolicy())
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(ErrorText.connectMessage(for: error, wantedStealth: false), Messages.serverUnreachable)
        }
    }
}
