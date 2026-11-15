import XCTest
@testable import VeritasCore

final class ErrorTextTests: XCTestCase {
    func testCloudflare1033PageIsNeverShown() throws {
        let response = APIResponse(status: 530, data: Data("error code: 1033".utf8))
        XCTAssertEqual(response.failure, .serverUnavailable(status: 530))
        XCTAssertEqual(response.failure.userMessage, Messages.serverUnreachable)
        XCTAssertEqual(
            Messages.serverUnreachable,
            "Can't reach VeritasVPN servers right now. Please check your internet connection or try again in a few minutes."
        )
    }

    func testEdgeBodyWithClientStatusStillMapsToUnreachable() {
        let html = "<!DOCTYPE html><html><head><title>Origin is unreachable | Cloudflare</title></head></html>"
        let response = APIResponse(status: 403, data: Data(html.utf8))
        XCTAssertEqual(response.failure, .serverUnavailable(status: 403))
    }

    func testHtml200FromCaptivePortalIsUnreachableNotParserError() {
        let response = APIResponse(status: 200, data: Data("<html><body>Login to Wi-Fi</body></html>".utf8))
        XCTAssertThrowsError(try response.decode(BillingStatus.self)) { error in
            XCTAssertEqual(error as? APIError, .serverUnavailable(status: 200))
            XCTAssertEqual(ErrorText.message(for: error), Messages.serverUnreachable)
        }
    }

    func testLooksLikeEdgeResponse() {
        XCTAssertTrue(ErrorText.looksLikeEdgeResponse("error code: 1033"))
        XCTAssertTrue(ErrorText.looksLikeEdgeResponse("Error Code 530"))
        XCTAssertTrue(ErrorText.looksLikeEdgeResponse("502 Bad Gateway"))
        XCTAssertTrue(ErrorText.looksLikeEdgeResponse("Ray ID: 8c0ffee"))
        XCTAssertTrue(ErrorText.looksLikeEdgeResponse("<html>"))
        XCTAssertFalse(ErrorText.looksLikeEdgeResponse("Incorrect email or password."))
        XCTAssertFalse(ErrorText.looksLikeEdgeResponse(""))
    }

    func testDisplaySafe() {
        XCTAssertTrue(ErrorText.isDisplaySafeMessage("Device limit reached. Disconnect another device."))
        XCTAssertFalse(ErrorText.isDisplaySafeMessage("Request failed (500)"))
        XCTAssertFalse(ErrorText.isDisplaySafeMessage("HTTP 404"))
        XCTAssertFalse(ErrorText.isDisplaySafeMessage("status: 401"))
        XCTAssertFalse(ErrorText.isDisplaySafeMessage("{\"error\":\"x\"}"))
        XCTAssertFalse(ErrorText.isDisplaySafeMessage("connect.invalid_config"))
        XCTAssertFalse(ErrorText.isDisplaySafeMessage("subscription_required"))
        XCTAssertFalse(ErrorText.isDisplaySafeMessage("The operation couldn’t be completed. (NSURLErrorDomain error -1004.)"))
        XCTAssertFalse(ErrorText.isDisplaySafeMessage("value was nil"))
        XCTAssertFalse(ErrorText.isDisplaySafeMessage(String(repeating: "a ", count: 150)))
    }

    func testSafeFallsBack() {
        XCTAssertEqual(ErrorText.safe("error code: 1033"), Messages.serverUnreachable)
        XCTAssertEqual(ErrorText.safe("invalid json"), Messages.generic)
        XCTAssertEqual(ErrorText.safe(nil, fallback: "Fallback."), "Fallback.")
        XCTAssertEqual(ErrorText.safe("  Plain sentence.  "), "Plain sentence.")
    }

    func testURLErrorsBecomeSentences() {
        XCTAssertEqual(ErrorText.message(for: URLError(.notConnectedToInternet)), Messages.offline)
        XCTAssertEqual(ErrorText.message(for: URLError(.timedOut)), Messages.serverTimeout)
        XCTAssertEqual(ErrorText.message(for: URLError(.cannotConnectToHost)), Messages.serverUnreachable)
        XCTAssertEqual(ErrorText.message(for: URLError(.serverCertificateUntrusted)), Messages.secureConnectionFailed)
        XCTAssertEqual(ErrorText.message(for: URLError(.unknown)), Messages.generic)
        let ns = NSError(domain: NSURLErrorDomain, code: NSURLErrorCannotFindHost)
        XCTAssertEqual(ErrorText.message(for: ns), Messages.serverUnreachable)
    }

    func testUnknownErrorsNeverLeakDescriptions() {
        struct Weird: Error, CustomStringConvertible { var description: String { "EXC_BAD_ACCESS code=1" } }
        XCTAssertEqual(ErrorText.message(for: Weird()), Messages.generic)
        XCTAssertEqual(ErrorText.connectMessage(for: Weird(), wantedStealth: false), Messages.connectFailed)
    }

    func testConnectMessages() {
        XCTAssertEqual(ErrorText.connectMessage(for: ProfileError.missingDNS, wantedStealth: false), Messages.invalidConfig)
        XCTAssertEqual(ErrorText.connectMessage(for: ProfileError.invalidPathPrefix, wantedStealth: true), Messages.stealthUnavailable)
        XCTAssertEqual(ErrorText.connectMessage(for: TunnelFailure.stealthComingSoon.nsError, wantedStealth: true), Messages.stealthComingSoon)
        XCTAssertEqual(ErrorText.connectMessage(for: TunnelFailure.invalidConfig, wantedStealth: true), Messages.stealthUnavailable)
        XCTAssertEqual(ErrorText.connectMessage(for: APIError.serverUnavailable(status: 530), wantedStealth: false), Messages.serverUnreachable)
    }

    func testTunnelFailureRoundTripsThroughNSError() {
        for failure in TunnelFailure.allCases {
            XCTAssertEqual(TunnelFailure(error: failure.nsError), failure)
            XCTAssertTrue(ErrorText.isDisplaySafeMessage(failure.userMessage), failure.userMessage)
        }
        XCTAssertNil(TunnelFailure(error: NSError(domain: "other", code: 1)))
    }

    func testPeerSetupErrors() {
        let limit = PeerSetupError(status: 403, serverMessage: "plan device limit reached (5). Upgrade to Premium for more devices", code: "plan_device_limit")
        XCTAssertEqual(limit.userMessage, Messages.deviceLimit)
        let premium = PeerSetupError(status: 403, serverMessage: "an active paid subscription is required", code: "subscription_required")
        XCTAssertEqual(premium.userMessage, Messages.premiumRequired)
        let raw = PeerSetupError(status: 400, serverMessage: "create peer failed", code: nil)
        XCTAssertEqual(raw.userMessage, Messages.peerSetupFailed)
        let sentence = PeerSetupError(status: 400, serverMessage: "This device was removed. Try again.", code: nil)
        XCTAssertEqual(sentence.userMessage, "This device was removed. Try again.")
        let code = PeerSetupError(status: 400, serverMessage: "public_key_required", code: nil)
        XCTAssertEqual(code.userMessage, Messages.peerSetupFailed)
    }

    func testEveryCoreMessageIsDisplaySafe() {
        let all = [
            Messages.serverUnreachable, Messages.serverTimeout, Messages.offline, Messages.secureConnectionFailed,
            Messages.generic, Messages.sessionExpired, Messages.connectFailed, Messages.invalidConfig,
            Messages.stealthUnavailable, Messages.stealthComingSoon, Messages.peerSetupFailed, Messages.deviceLimit,
            Messages.premiumRequired, Messages.handshakeTimeout, Messages.verifyEmailFirst, Messages.incorrectCredentials,
        ]
        for message in all { XCTAssertTrue(ErrorText.isDisplaySafeMessage(message), message) }
    }

    func testRedactionScrubsAddressesAndKeys() {
        let text = "endpoint 192.168.77.6:51820 v6 [2001:db8::1]:443 key \(Fixtures.key(7)) Bearer abc.def"
        let scrubbed = Redaction.scrub(text)
        XCTAssertFalse(scrubbed.contains("192.168"))
        XCTAssertFalse(scrubbed.contains("2001:db8"))
        XCTAssertFalse(scrubbed.contains(Fixtures.key(7)))
        XCTAssertFalse(scrubbed.contains("abc.def"))
    }
}
