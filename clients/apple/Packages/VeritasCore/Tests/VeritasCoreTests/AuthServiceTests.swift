import XCTest
@testable import VeritasCore

final class AuthServiceTests: XCTestCase {
    private func service(_ transport: FakeTransport, store: InMemoryCredentialStore = InMemoryCredentialStore()) -> AuthService {
        let client = Fixtures.client(transport)
        return AuthService(api: client, session: SessionManager(store: store, api: client))
    }

    func testSignInStoresSessionAndNormalizesEmail() async throws {
        let store = InMemoryCredentialStore()
        let transport = FakeTransport { _ in (200, Fixtures.json(["access_token": "a", "refresh_token": "r", "account_id": "acct"])) }
        let stored = try await service(transport, store: store).signIn(email: "  Person@Example.TEST ", password: "pw")
        XCTAssertEqual(stored.email, "person@example.test")
        XCTAssertEqual(store.load()?.refreshToken, "r")
        XCTAssertEqual(Fixtures.body(of: transport.requests[0])["email"] as? String, "person@example.test")
    }

    func testSignInNeedsVerification() async {
        let transport = FakeTransport { _ in (403, #"{"error":"Verify your email before signing in"}"#) }
        do {
            try await service(transport).signIn(email: "a@b.test", password: "pw")
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(error as? AuthFailure, .verificationRequired(email: "a@b.test"))
        }
    }

    func testSignInTurnstileRequired() async {
        let transport = FakeTransport { _ in (403, #"{"error":"security check required"}"#) }
        do {
            try await service(transport).signIn(email: "a@b.test", password: "pw")
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(error as? AuthFailure, .turnstileRequired)
        }
    }

    func testSignInWrongPasswordIsFriendly() async {
        let transport = FakeTransport { _ in (401, #"{"error":"invalid email or password"}"#) }
        do {
            try await service(transport).signIn(email: "a@b.test", password: "pw")
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(ErrorText.message(for: error), Messages.incorrectCredentials)
        }
    }

    func testSignInDuringOutageShowsUnreachable() async {
        let transport = FakeTransport { _ in (530, "error code: 1033") }
        do {
            try await service(transport).signIn(email: "a@b.test", password: "pw")
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(ErrorText.message(for: error), Messages.serverUnreachable)
        }
    }

    func testRawServerTextIsNeverShown() {
        XCTAssertEqual(AuthService.friendlyAuthMessage(status: 400, serverMessage: "invalid json"), Messages.signInServiceUnreachable)
        XCTAssertEqual(AuthService.friendlyAuthMessage(status: 418, serverMessage: nil), Messages.signInServiceUnreachable)
        XCTAssertEqual(AuthService.friendlyAuthMessage(status: 429, serverMessage: nil), Messages.tooManyAttempts)
        XCTAssertEqual(AuthService.friendlyAuthMessage(status: 400, serverMessage: "account not found"), Messages.accountIdNotFound)
        XCTAssertEqual(AuthService.friendlyAuthMessage(status: 502, serverMessage: "Bad gateway"), Messages.serverUnreachable)
    }

    func testSignUpVerificationRequired() async {
        let transport = FakeTransport { _ in (200, Fixtures.json(["verification_required": true, "account_id": "acct"])) }
        do {
            _ = try await service(transport).signUp(email: "a@b.test", password: "Password123", turnstileToken: "t")
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(error as? AuthFailure, .verificationRequired(email: "a@b.test"))
        }
    }

    func testSignUpExistingAccount() async {
        let transport = FakeTransport { _ in (409, #"{"error":"An account with this email already exists."}"#) }
        do {
            _ = try await service(transport).signUp(email: "a@b.test", password: "Password123", turnstileToken: "t")
            XCTFail("expected failure")
        } catch {
            XCTAssertEqual(error as? AuthFailure, .accountAlreadyExists(email: "a@b.test"))
        }
    }

    func testAnonymousAccount() async throws {
        let store = InMemoryCredentialStore()
        let transport = FakeTransport { _ in (200, Fixtures.json(["access_token": "a", "refresh_token": "r", "account_id": "1234-5678"])) }
        let stored = try await service(transport, store: store).registerAnonymous(turnstileToken: "t")
        XCTAssertTrue(stored.isAnonymous)
        XCTAssertEqual(stored.accountId, "1234-5678")
        XCTAssertEqual(Fixtures.body(of: transport.requests[0])["turnstile_token"] as? String, "t")
    }

    func testPasswordRules() {
        XCTAssertEqual(PasswordRules.problem(password: "short", confirmation: "short"), Messages.passwordTooShort)
        XCTAssertEqual(PasswordRules.problem(password: "alllowercase1", confirmation: ""), Messages.passwordNeedsUppercase)
        XCTAssertEqual(PasswordRules.problem(password: "ALLUPPERCASE1", confirmation: ""), Messages.passwordNeedsLowercase)
        XCTAssertEqual(PasswordRules.problem(password: "NoDigitsHere", confirmation: ""), Messages.passwordNeedsNumber)
        XCTAssertEqual(PasswordRules.problem(password: "GoodPassword1", confirmation: ""), Messages.confirmPassword)
        XCTAssertEqual(PasswordRules.problem(password: "GoodPassword1", confirmation: "Other"), Messages.passwordsDontMatch)
        XCTAssertNil(PasswordRules.problem(password: "GoodPassword1", confirmation: "GoodPassword1"))
        XCTAssertEqual(PasswordRules.strength("GoodPassword1"), 4)
    }
}
