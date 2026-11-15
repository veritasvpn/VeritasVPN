import Foundation
#if canImport(CryptoKit)
import CryptoKit

/// A WireGuard (X25519) key pair. Generated fresh for every connect, like
/// Android; the private key only leaves the app through the shared Keychain.
public struct WireGuardKeyPair: Sendable {
    public let privateKey: String
    public let publicKey: String

    public init() {
        let key = Curve25519.KeyAgreement.PrivateKey()
        privateKey = key.rawRepresentation.base64EncodedString()
        publicKey = key.publicKey.rawRepresentation.base64EncodedString()
    }

    /// Derives the public key from a base64 private key.
    public init?(privateKey base64: String) {
        guard let data = Data(base64Encoded: base64), data.count == 32,
              let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: data) else { return nil }
        privateKey = base64
        publicKey = key.publicKey.rawRepresentation.base64EncodedString()
    }
}
#endif
